package com.roifewu.orgenda

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.roifewu.orgenda.core.ConfigurationCodec
import com.roifewu.orgenda.core.WorkspaceConfig
import com.roifewu.orgenda.data.FileEntity
import com.roifewu.orgenda.data.WorkspaceDatabase
import com.roifewu.orgenda.data.WorkspaceEntity
import com.roifewu.orgenda.data.WorkspaceRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigurationEditSafetyTest {
    private lateinit var realApp: OrgendaApplication
    private lateinit var app: OrgendaApplication
    private lateinit var repository: WorkspaceRepository
    private lateinit var root: File
    private lateinit var models: ViewModelStore
    private lateinit var model: OrgendaViewModel
    private lateinit var workspaceId: String
    private lateinit var baseline: WorkspaceConfig
    private val preferenceNames = mutableSetOf<String>()
    private val workspaceIds = mutableSetOf<String>()

    @Before
    fun setup() = runBlocking {
        realApp = ApplicationProvider.getApplicationContext()
        realApp.ready.await()
        val prefix = "configuration-edit-${UUID.randomUUID()}"
        root = File(realApp.cacheDir, prefix).apply { mkdirs() }
        val isolated =
            object : ContextWrapper(realApp) {
                override fun getApplicationContext(): Context = this

                override fun getFilesDir() = root

                override fun getSharedPreferences(name: String, mode: Int) =
                    realApp.getSharedPreferences(
                        "$prefix-$name".also { preferenceNames += it },
                        mode,
                    )
            }
        // A separate Application avoids replacing the QA process's repository or ready job.
        // onCreate is deliberately not called: no periodic sync or reminder collector is started.
        app =
            Instrumentation.newApplication(OrgendaApplication::class.java, isolated)
                as OrgendaApplication
        val database = Room.inMemoryDatabaseBuilder(isolated, WorkspaceDatabase::class.java).build()
        repository = WorkspaceRepository(isolated, database)
        repository.start()
        baseline = WorkspaceConfig.defaults()
        repository.saveConfiguration(baseline, null)
        repository.sync(pull = false)
        workspaceId = repository.state.value.workspace!!.id
        workspaceIds += workspaceId
        app.repository = repository
        app.settings = LocalSettings(isolated)
        app.ready = CompletableDeferred(Unit)
        createModel()
        awaitConfiguration { it.workspaceId == workspaceId && it.value == baseline }
        Unit
    }

    @After
    fun cleanup() = runBlocking {
        if (::models.isInitialized) withContext(Dispatchers.Main.immediate) { models.clear() }
        if (::realApp.isInitialized) {
            // Successful saves enqueue only these random fixture workspace IDs. The real
            // repository has no such IDs, so a worker cannot write to the fixture or QA files.
            workspaceIds.forEach { id ->
                WorkManager.getInstance(realApp)
                    .cancelUniqueWork("orgenda-sync-$id")
                    .result
                    .get(10, TimeUnit.SECONDS)
            }
        }
        if (::app.isInitialized) app.scope.cancel()
        if (::repository.isInitialized) {
            repository.engine.close()
            repository.database.close()
        }
        if (::root.isInitialized) root.deleteRecursively()
        if (::realApp.isInitialized) preferenceNames.forEach { realApp.deleteSharedPreferences(it) }
    }

    private suspend fun createModel() =
        withContext(Dispatchers.Main.immediate) {
            models = ViewModelStore()
            model =
                ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory(app))[
                    OrgendaViewModel::class.java]
        }

    private suspend fun awaitConfiguration(
        predicate: (ConfigurationEdit) -> Boolean
    ): ConfigurationEdit =
        withTimeout(6000) { model.configuration.filterNotNull().first(predicate) }

    private suspend fun configFile(id: String = workspaceId): FileEntity =
        repository.database.dao().files(id).single { it.path == "config.json" && !it.trashed }

    private suspend fun committedConfig(id: String = workspaceId): WorkspaceConfig =
        ConfigurationCodec.decode(repository.text(configFile(id)))

    private fun candidate(from: WorkspaceConfig = baseline, advance: Int = 27) =
        from.copy(reminders = from.reminders.copy(advanceMinutes = advance))

    private fun failConfigurationWrites() {
        repository.database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_configuration_write BEFORE UPDATE ON files WHEN OLD.path='config.json' " +
                "BEGIN SELECT RAISE(ABORT, 'injected configuration commit failure'); END"
        )
    }

    private fun allowConfigurationWrites() {
        repository.database.openHelper.writableDatabase.execSQL(
            "DROP TRIGGER fail_configuration_write"
        )
    }

    private fun assertInjectedFailure(failure: Throwable?) {
        assertNotNull("The transaction must fail, not silently accept the candidate", failure)
        val messages = generateSequence(failure) { it.cause }.joinToString("\n") { it.toString() }
        assertTrue(messages, messages.contains("injected configuration commit failure"))
    }

    @Test
    fun invalidRecoveryDraftRetainsRawTextAndUsesTheCommittedConfiguration() = runBlocking {
        val raw = """{"version":1,"workflow":{"sequences":[]}}"""
        withContext(Dispatchers.Main.immediate) {
            model.configureRaw(raw)
            models.clear()
        }
        createModel()
        val recovered = awaitConfiguration { it.dirty && it.raw == raw }
        assertEquals(baseline, recovered.value)
        assertEquals(configFile().contentBlob, recovered.baseline)
        assertTrue(
            runCatching { model.saveConfigurationNow() }.exceptionOrNull()
                is IllegalArgumentException
        )
        assertEquals(raw, model.configuration.value!!.raw)
        assertTrue(model.configuration.value!!.dirty)
        assertEquals(baseline, committedConfig())
    }

    @Test
    fun failedCommitKeepsOldConfigurationAndTheSameRequestCanRetry() = runBlocking {
        val before = configFile()
        val edited = candidate()
        failConfigurationWrites()
        assertInjectedFailure(
            runCatching { model.saveConfigurationEdit(edited, baseline, workspaceId) }
                .exceptionOrNull()
        )

        val retained = model.configuration.value!!
        assertEquals(baseline, retained.value)
        assertEquals(baseline, ConfigurationCodec.decode(retained.raw))
        assertEquals(before.contentBlob, retained.baseline)
        assertFalse(retained.dirty)
        assertFalse(retained.saving)
        assertNotNull(retained.error)
        assertEquals(baseline, committedConfig())
        assertFalse(
            app.getSharedPreferences("configuration-drafts", Context.MODE_PRIVATE)
                .contains(workspaceId)
        )

        allowConfigurationWrites()
        // Waiting beyond the normal autosave debounce catches a leaked candidate draft.
        delay(700)
        assertEquals(before.contentBlob, configFile().contentBlob)
        model.saveConfigurationEdit(edited, baseline, workspaceId)
        val saved = awaitConfiguration { it.value == edited && !it.dirty && !it.saving }
        assertNull(saved.error)
        assertEquals(edited, committedConfig())
        assertEquals(edited, repository.state.value.configuration)
        assertEquals(configFile().contentBlob, saved.baseline)
    }

    @Test
    fun failedCandidatePreservesAnExistingAutosaveRecoveryDraft() = runBlocking {
        val priorDraft = baseline.copy(files = baseline.files.copy(inbox = "previous-draft.org"))
        val edited = candidate(priorDraft)
        failConfigurationWrites()
        withContext(Dispatchers.Main.immediate) { model.configure(priorDraft) }
        val oldRecovery =
            app.getSharedPreferences("configuration-drafts", Context.MODE_PRIVATE)
                .getString(workspaceId, null)
        assertNotNull(oldRecovery)

        assertInjectedFailure(
            runCatching { model.saveConfigurationEdit(edited, priorDraft, workspaceId) }
                .exceptionOrNull()
        )
        val retained = model.configuration.value!!
        assertEquals(priorDraft, retained.value)
        assertEquals(priorDraft, ConfigurationCodec.decode(retained.raw))
        assertTrue(retained.dirty)
        assertFalse(retained.saving)
        val recovery =
            app.getSharedPreferences("configuration-drafts", Context.MODE_PRIVATE)
                .getString(workspaceId, null)
        assertEquals(oldRecovery, recovery)
        assertEquals(
            priorDraft,
            ConfigurationCodec.decode(
                Json.parseToJsonElement(recovery!!).jsonObject.getValue("raw").jsonPrimitive.content
            ),
        )
        assertEquals(baseline, committedConfig())
    }

    @Test
    fun staleExpectedConfigurationCannotOverwriteANewerCommit() = runBlocking {
        val newer = candidate(advance = 42)
        repository.saveConfiguration(newer, configFile().contentBlob, workspaceId)
        awaitConfiguration { it.value == newer && !it.dirty && !it.saving }
        val before = configFile()

        val failure = runCatching {
            model.saveConfigurationEdit(candidate(), baseline, workspaceId)
        }
            .exceptionOrNull()
        assertTrue("A stale editor must be rejected", failure is IllegalArgumentException)
        assertEquals(newer, committedConfig())
        assertEquals(before.contentBlob, configFile().contentBlob)
        assertEquals(before.generation, configFile().generation)
        assertEquals(newer, model.configuration.value!!.value)
        assertFalse(model.configuration.value!!.saving)
    }

    @Test
    fun rejectedStaleRequestDoesNotCancelAnotherDraftsPendingAutosave() = runBlocking {
        val priorDraft = candidate(advance = 43)
        withContext(Dispatchers.Main.immediate) { model.configure(priorDraft) }
        val failure = runCatching {
            model.saveConfigurationEdit(candidate(), baseline, workspaceId)
        }
            .exceptionOrNull()
        assertTrue("A stale request must be rejected", failure is IllegalArgumentException)

        awaitConfiguration { it.value == priorDraft && !it.dirty && !it.saving }
        assertEquals(priorDraft, committedConfig())
        assertFalse(
            app.getSharedPreferences("configuration-drafts", Context.MODE_PRIVATE)
                .contains(workspaceId)
        )
    }

    @Test
    fun editorFromPreviousWorkspaceCannotCreateConfigurationInCurrentWorkspace() = runBlocking {
        val original = configFile()
        val secondId = UUID.randomUUID().toString()
        workspaceIds += secondId
        val secondRoot = File(root, "second").apply { mkdirs() }
        repository.database
            .dao()
            .putWorkspace(WorkspaceEntity(secondId, "private", secondRoot.path, "Second"))
        repository.switchWorkspace(secondId)
        awaitConfiguration { it.workspaceId == secondId }

        val failure = runCatching {
            model.saveConfigurationEdit(candidate(), baseline, workspaceId)
        }
            .exceptionOrNull()
        assertTrue(
            "The old sheet must not save into the new workspace",
            failure is IllegalArgumentException,
        )
        assertEquals(secondId, repository.state.value.workspace!!.id)
        assertTrue(repository.database.dao().files(secondId).none { it.path == "config.json" })
        assertEquals(original.contentBlob, configFile(workspaceId).contentBlob)
        assertEquals(baseline, model.configuration.value!!.value)
    }

    @Test
    fun recreatedViewModelCanReplayACommittedRequestWithoutWritingAgain() = runBlocking {
        val edited = candidate()
        model.saveConfigurationEdit(edited, baseline, workspaceId)
        val committed = configFile()
        withContext(Dispatchers.Main.immediate) { models.clear() }
        createModel()
        awaitConfiguration { it.value == edited && !it.dirty && !it.saving }
        failConfigurationWrites()

        // A sheet restored after its successful commit still holds the original expected value.
        // The trigger proves replay does not perform a second database update.
        model.saveConfigurationEdit(edited, baseline, workspaceId)
        assertEquals(committed.contentBlob, configFile().contentBlob)
        assertEquals(committed.generation, configFile().generation)
        assertEquals(edited, committedConfig())
        assertFalse(model.configuration.value!!.dirty)
        assertFalse(model.configuration.value!!.saving)
        assertNull(model.configuration.value!!.error)
    }
}
