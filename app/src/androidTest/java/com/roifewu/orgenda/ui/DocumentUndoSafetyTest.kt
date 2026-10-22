package com.roifewu.orgenda.ui

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.roifewu.orgenda.LocalSettings
import com.roifewu.orgenda.OrgendaApplication
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.HeadingPlacement
import com.roifewu.orgenda.data.WorkspaceDatabase
import com.roifewu.orgenda.data.WorkspaceRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentUndoSafetyTest {
    private lateinit var realApp: OrgendaApplication
    private lateinit var app: OrgendaApplication
    private lateinit var repository: WorkspaceRepository
    private lateinit var root: File
    private lateinit var models: ViewModelStore
    private lateinit var model: OrgendaViewModel
    private val preferences = mutableSetOf<String>()
    private var workspaceId: String? = null
    private val original = "* TODO 中文🧪\nSCHEDULED: <2026-10-22 Thu>\n- [ ] Check\n"

    @Before
    fun setup() = runBlocking {
        realApp = ApplicationProvider.getApplicationContext()
        realApp.ready.await()
        val prefix = "document-undo-${UUID.randomUUID()}"
        root = File(realApp.cacheDir, prefix).apply { mkdirs() }
        val isolated =
            object : ContextWrapper(realApp) {
                override fun getApplicationContext(): Context = this

                override fun getFilesDir() = root

                override fun getSharedPreferences(name: String, mode: Int) =
                    realApp.getSharedPreferences("$prefix-$name".also { preferences += it }, mode)
            }
        app =
            Instrumentation.newApplication(OrgendaApplication::class.java, isolated)
                as OrgendaApplication
        repository =
            WorkspaceRepository(
                isolated,
                Room.inMemoryDatabaseBuilder(isolated, WorkspaceDatabase::class.java).build(),
            )
        repository.start()
        workspaceId = repository.state.value.workspace!!.id
        app.repository = repository
        app.settings = LocalSettings(isolated)
        app.ready = CompletableDeferred(Unit)
        withContext(Dispatchers.Main.immediate) {
            models = ViewModelStore()
            model =
                ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory(app))[
                    OrgendaViewModel::class.java]
        }
        val file = repository.create("Undo.org", original)
        model.open(file).join()
        awaitText(original)
        Unit
    }

    @After
    fun cleanup() = runBlocking {
        if (::models.isInitialized) withContext(Dispatchers.Main.immediate) { models.clear() }
        workspaceId?.let {
            WorkManager.getInstance(realApp)
                .cancelUniqueWork("orgenda-sync-$it")
                .result
                .get(10, TimeUnit.SECONDS)
        }
        if (::app.isInitialized) app.scope.cancel()
        if (::repository.isInitialized) {
            repository.engine.close()
            repository.database.close()
        }
        if (::root.isInitialized) root.deleteRecursively()
        if (::realApp.isInitialized) preferences.forEach { realApp.deleteSharedPreferences(it) }
    }

    private suspend fun awaitText(text: String) =
        withTimeout(8000) {
            model.editor.filterNotNull().first { it.text == text && !it.dirty && !it.saving }
        }

    private fun native(): NativeSourceEditor {
        val current = model.editor.value!!
        val history = model.editorHistories.getOrPut(current.session) { EditorHistory() }
        return NativeSourceEditor(
                ContextThemeWrapper(app, androidx.appcompat.R.style.Theme_AppCompat),
                history,
            )
            .apply {
                display(current.text)
                changed = model::edit
            }
    }

    private suspend fun save(text: String) {
        val file = repository.state.value.files.single { it.path == "Undo.org" }
        repository.save(file.id, text, file.contentBlob)
        awaitText(text)
    }

    @Test
    fun previewChangesPreserveEarlierTypingHistoryAndUnicodeSelection() = runBlocking {
        val typed = original + "正文🧪"
        withContext(Dispatchers.Main.immediate) {
            val view = native()
            try {
                view.setSelection(view.length())
                val connection = view.onCreateInputConnection(EditorInfo())!!
                connection.setComposingText("zhong", 1)
                connection.setComposingText("正文🧪", 1)
                view.suspendInput()
                assertEquals(typed, view.text.toString())
            } finally {
                view.dispose()
            }
        }
        model.saveNow()
        val checked = typed.replace("[ ]", "[X]")
        save(checked)
        val done = checked.replace("TODO", "DONE")
        save(done)
        val dated = done.replace("2026-10-22 Thu", "2026-10-23 Fri")
        save(dated)
        withContext(Dispatchers.Main.immediate) {
            val view = native()
            try {
                listOf(done, checked, typed, original).forEach { expected ->
                    view.undo()
                    assertEquals(expected, view.text.toString())
                }
                assertEquals(original.length, view.selectionStart)
                assertFalse(view.canUndo)
                listOf(typed, checked, done, dated).forEach { expected ->
                    view.redo()
                    assertEquals(expected, view.text.toString())
                }
                assertFalse(view.canRedo)
            } finally {
                view.dispose()
            }
        }
        model.saveNow()
        assertEquals(
            dated,
            repository.text(repository.state.value.files.single { it.path == "Undo.org" }),
        )
    }

    @Test
    fun conflatedLocalChangesRemainUndoableEvenWhenTheFinalSourceMatchesTheOriginal() =
        runBlocking {
            val checked = original.replace("[ ]", "[X]")
            withContext(Dispatchers.Main.immediate) {
                // Delay the Main collector until both commits finish, reproducing StateFlow
                // conflation.
                runBlocking(Dispatchers.IO) {
                    val file = repository.state.value.files.single { it.path == "Undo.org" }
                    val first = repository.save(file.id, checked, file.contentBlob)
                    repository.save(file.id, original, first.contentBlob)
                }
            }
            val generation =
                repository.state.value.files.single { it.path == "Undo.org" }.generation
            withTimeout(8000) {
                model.editor.filterNotNull().first { it.file.generation == generation }
            }
            withContext(Dispatchers.Main.immediate) {
                val view = native()
                try {
                    view.undo()
                    assertEquals(checked, view.text.toString())
                    view.undo()
                    assertEquals(original, view.text.toString())
                    assertFalse(view.canUndo)
                } finally {
                    view.dispose()
                }
            }
            model.saveNow()
        }

    @Test
    fun externalReplacementClearsHistoryAndFailedSavesCannotCreateUndoEntries() = runBlocking {
        save(original.replace("[ ]", "[X]"))
        val before = repository.state.value.files.single { it.path == "Undo.org" }
        val edits = repository.state.value.localDocumentEdits.size
        assertTrue(
            runCatching { repository.save(before.id, "wrong draft", "stale baseline") }.isFailure
        )
        assertEquals(edits, repository.state.value.localDocumentEdits.size)
        val external = "* TODO 外部版本🧪\n"
        repository.database
            .dao()
            .put(
                before.copy(
                    baseBlob = repository.blobs.put(external),
                    draftBlob = null,
                    generation = before.generation + 1,
                )
            )
        repository.publish()
        awaitText(external)
        withContext(Dispatchers.Main.immediate) {
            val view = native()
            try {
                assertFalse(view.canUndo)
                assertFalse(view.canRedo)
                view.undo()
                assertEquals(external, view.text.toString())
            } finally {
                view.dispose()
            }
        }
        save(external + "- [X] New\n")
        withContext(Dispatchers.Main.immediate) {
            val view = native()
            try {
                view.undo()
                assertEquals(external, view.text.toString())
                assertFalse(view.canUndo)
            } finally {
                view.dispose()
            }
        }
        model.saveNow()
    }

    @Test
    fun headingMoveAddsExactlyOneUndoEntryDespiteTheRepositoryPublishingFirst() = runBlocking {
        val before = original + "* Other\n"
        save(before)
        val document = model.editor.value!!.document!!
        model
            .moveHeading(
                document.headings.first(),
                document.headings.last(),
                HeadingPlacement.CHILD,
            )
            .join()
        val moved =
            withTimeout(8000) { model.editor.filterNotNull().first { it.headingMove != null } }
        withContext(Dispatchers.Main.immediate) {
            val view = native()
            try {
                assertEquals(2, view.history.undo.size)
                view.undo()
                assertEquals(before, view.text.toString())
                view.undo()
                assertEquals(original, view.text.toString())
                view.redo()
                assertEquals(before, view.text.toString())
                view.redo()
                assertEquals(moved.text, view.text.toString())
            } finally {
                view.dispose()
            }
        }
        model.saveNow()
    }
}
