package com.roifewu.orgenda

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import com.roifewu.orgenda.core.ConfigurationCodec
import com.roifewu.orgenda.core.WorkspaceConfig
import com.roifewu.orgenda.data.FileEntity
import com.roifewu.orgenda.data.WorkspaceEntity
import com.roifewu.orgenda.data.WorkspaceSnapshot
import java.io.IOException
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConfigurationEditorTest {
    private val baseline = WorkspaceConfig.defaults()
    private val file = FileEntity("config", "workspace", "config.json", baseBlob = "base")
    private val workspace =
        MutableStateFlow(
            WorkspaceSnapshot(
                workspace = WorkspaceEntity("workspace", "private", "unused", "Test"),
                files = listOf(file),
                configuration = baseline,
            )
        )
    private val savedWorkspaces = mutableListOf<String>()
    private lateinit var scope: CoroutineScope
    private lateinit var recovery: SharedPreferences

    @Before
    fun setup() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        recovery =
            RuntimeEnvironment.getApplication().getSharedPreferences(
                "configuration-editor-${UUID.randomUUID()}",
                Context.MODE_PRIVATE,
            )
    }

    @After
    fun cleanup() {
        scope.cancel()
        recovery.edit().clear().commit()
    }

    private fun editor(
        save: suspend (WorkspaceConfig, String?, String) -> FileEntity = { _, _, _ ->
            error("Unexpected configuration write")
        }
    ) = ConfigurationEditor(workspace, save, scope, recovery) { savedWorkspaces += it }
        .also { it.accept(workspace.value) }

    private fun candidate(advanceMinutes: Int) =
        baseline.copy(reminders = baseline.reminders.copy(advanceMinutes = advanceMinutes))

    @Test
    fun autosaveDebouncesRapidChangesAndSavesOnlyTheLatestDraft() {
        val saved = mutableListOf<WorkspaceConfig>()
        val editor = editor { value, _, _ ->
            saved += value
            file.copy(draftBlob = "saved")
        }
        val looper = shadowOf(Looper.getMainLooper())
        editor.configure(candidate(10))
        looper.idleFor(Duration.ofMillis(250))
        editor.configure(candidate(20))
        looper.idleFor(Duration.ofMillis(499))
        assertTrue(saved.isEmpty())
        looper.idleFor(Duration.ofMillis(1))
        assertEquals(listOf(candidate(20)), saved)
        assertFalse(editor.state.value!!.dirty)
        assertNull(recovery.getString("workspace", null))
    }

    @Test
    fun typingDuringASaveRetainsTheNewDraftWithTheCommittedBaseline() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = candidate(10)
        val newer = candidate(20)
        val saved = file.copy(draftBlob = "saved")
        val editor = editor { value, expected, workspaceId ->
            assertEquals(first, value)
            assertEquals("base", expected)
            assertEquals("workspace", workspaceId)
            started.complete(Unit)
            finish.await()
            workspace.value = workspace.value.copy(configuration = value, files = listOf(saved))
            saved
        }
        editor.configure(first)
        val saving = async(start = CoroutineStart.UNDISPATCHED) { editor.saveNow() }
        started.await()
        assertTrue(editor.state.value!!.saving)
        editor.configure(newer)
        finish.complete(Unit)
        saving.await()

        val retained = editor.state.value!!
        assertEquals(newer, retained.value)
        assertEquals("saved", retained.baseline)
        assertTrue(retained.dirty)
        assertFalse(retained.saving)
        val restored = editor().state.value!!
        assertEquals(newer, restored.value)
        assertEquals(retained.raw, restored.raw)
        assertEquals("saved", restored.baseline)
        assertEquals(listOf("workspace"), savedWorkspaces)
    }

    @Test
    fun aFailedCommitKeepsRecoveryAndCanRetry() = runBlocking {
        var fail = true
        val editor = editor { _, _, _ ->
            if (fail) throw IOException("injected commit failure")
            file.copy(draftBlob = "saved")
        }
        editor.configure(candidate(20))
        val recoveryBefore = recovery.getString("workspace", null)
        assertTrue(runCatching { editor.saveNow() }.exceptionOrNull() is IOException)
        assertEquals(recoveryBefore, recovery.getString("workspace", null))
        assertEquals("base", editor.state.value!!.baseline)
        assertEquals("injected commit failure", editor.state.value!!.error)
        assertTrue(editor.state.value!!.dirty)
        assertFalse(editor.state.value!!.saving)
        assertTrue(savedWorkspaces.isEmpty())

        fail = false
        editor.saveNow()
        assertFalse(editor.state.value!!.dirty)
        assertNull(editor.state.value!!.error)
        assertNull(recovery.getString("workspace", null))
        assertEquals(listOf("workspace"), savedWorkspaces)
    }

    @Test
    fun oldWorkspaceSaveCannotReplaceTheNewWorkspacesDraft() = runBlocking {
        val finish = CompletableDeferred<Unit>()
        val editor = editor { _, _, _ ->
            finish.await()
            file.copy(draftBlob = "saved")
        }
        val original = candidate(10)
        editor.configure(original)
        val saving = async(start = CoroutineStart.UNDISPATCHED) { editor.saveNow() }
        workspace.value = workspace.value.copy(
            workspace = WorkspaceEntity("other", "private", "unused", "Other"),
            files = emptyList(),
        )
        editor.accept(workspace.value)
        editor.configure(candidate(30))
        val otherDraft = editor.state.value
        finish.complete(Unit)
        saving.await()

        assertEquals(otherDraft, editor.state.value)
        assertNotNull(recovery.getString("other", null))
        workspace.value = workspace.value.copy(
            workspace = WorkspaceEntity("workspace", "private", "unused", "Original"),
            files = listOf(file),
        )
        editor.accept(workspace.value)
        assertEquals(original, editor.state.value!!.value)
        assertTrue(editor.state.value!!.dirty)
    }

    @Test
    fun invalidRawDraftSurvivesRecreationAndDiscardRestoresTheSnapshot() = runBlocking {
        val editor = editor()
        val raw = "{\"version\":1, unfinished"
        editor.configureRaw(raw)
        assertTrue(runCatching { editor.saveNow() }.isFailure)

        val restored = editor()
        assertEquals(raw, restored.state.value!!.raw)
        assertEquals(baseline, restored.state.value!!.value)
        assertTrue(restored.state.value!!.dirty)
        restored.discard()
        assertEquals(ConfigurationCodec.encode(baseline), restored.state.value!!.raw)
        assertFalse(restored.state.value!!.dirty)
        assertNull(recovery.getString("workspace", null))
    }

    @Test
    fun staleSheetCannotOverwriteANewerConfiguration() = runBlocking {
        val editor = editor()
        val newer = candidate(20)
        workspace.value = workspace.value.copy(configuration = newer)
        editor.accept(workspace.value)
        val failure = runCatching { editor.saveEdit(candidate(30), baseline, "workspace") }

        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertEquals(newer, editor.state.value!!.value)
        assertFalse(editor.state.value!!.saving)
        assertTrue(savedWorkspaces.isEmpty())
    }
}
