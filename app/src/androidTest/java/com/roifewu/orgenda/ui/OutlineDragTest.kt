package com.roifewu.orgenda.ui

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.roifewu.orgenda.LocalSettings
import com.roifewu.orgenda.OrgendaApplication
import com.roifewu.orgenda.OrgendaViewModel
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
import org.junit.Rule
import org.junit.Test

class OutlineDragTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var realApp: OrgendaApplication
    private lateinit var app: OrgendaApplication
    private lateinit var repository: WorkspaceRepository
    private lateinit var root: File
    private lateinit var models: ViewModelStore
    private lateinit var model: OrgendaViewModel
    private val preferences = mutableSetOf<String>()
    private var workspaceId: String? = null
    private var gestureView: View? = null
    private var closes = 0
    private val source = "* TODO Source\n** Child\n*** Leaf\n* Destination\n"

    @Before
    fun setup() = runBlocking {
        realApp = ApplicationProvider.getApplicationContext()
        realApp.ready.await()
        val prefix = "outline-drag-${UUID.randomUUID()}"
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
        open(source)
    }

    private suspend fun open(text: String) {
        val file = repository.create("Outline-${UUID.randomUUID()}.org", text)
        model.open(file).join()
        withTimeout(8000) { model.editor.filterNotNull().first { it.text == text && !it.dirty } }
    }

    private fun showOutline(inDocument: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                val editor by model.editor.collectAsState()
                editor?.let {
                    if (inDocument) DocumentScreen(model, it, {}, { closes++ }, {})
                    else OutlineSheet(model, it) { closes++ }
                }
            }
        }
        if (inDocument) compose.onNodeWithContentDescription(realApp.label("outline")).performClick()
        val first = model.editor.value!!.document!!.headings.first().start
        compose.onNodeWithTag("outline.heading.$first").assertIsDisplayed()
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

    private fun showPreview() {
        compose.setContent {
            gestureView = LocalView.current
            MaterialTheme {
                val editor by model.editor.collectAsState()
                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    editor?.let { OrgPreview(model, it, { closes++ }, { closes++ }) }
                }
            }
        }
        compose.onNodeWithTag("document.preview").assertIsDisplayed()
    }

    @Test
    fun previewTitleTextDragsAcrossParentsAndBackWithoutOpeningEditor() = runBlocking {
        open("* Source\n   Body stays intact\n** Child\n* Destination\n")
        showPreview()
        fun heading(title: String) = model.editor.value!!.document!!.headings.single { it.title == title }
        val target = heading("Destination")
        nativeDrag(
            compose.onNodeWithTag("preview.heading.title.0").fetchSemanticsNode().boundsInRoot.center,
            compose.onNodeWithTag("heading.drop.${target.start}").fetchSemanticsNode().boundsInRoot.center,
        )
        compose.waitUntil(8000) { heading("Source").level == 2 || repository.state.value.error != null }
        assertNull(repository.state.value.error)
        assertEquals(listOf("Destination"), heading("Source").ancestors)
        compose.waitForIdle()
        val parent = compose.onNodeWithTag("heading.drop.0").fetchSemanticsNode().boundsInRoot
        nativeDrag(
            compose.onNodeWithTag("preview.heading.title.${heading("Source").start}").fetchSemanticsNode().boundsInRoot.center,
            Offset(parent.left + 2f, parent.top + 2f),
        )
        compose.waitUntil(8000) { heading("Source").level == 1 }
        assertEquals("* Source\n   Body stays intact\n** Child\n* Destination\n", repository.text(model.editor.value!!.file))
        assertEquals(0, closes)
        compose.onNodeWithTag("heading.drag.0").assertDoesNotExist()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(realApp.getExternalFilesDir(null), "preview-long-press.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        compose.onNodeWithTag("preview.heading.title.0").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun previewTrailingBodyAndBlankSpaceAcceptDropsAndCancelDoesNotEdit() = runBlocking {
        val text = "* Source\n   Source body\n* Destination\n   Target body\n"
        open(text)
        showPreview()
        val start = compose.onNodeWithTag("preview.heading.title.0").fetchSemanticsNode().boundsInRoot.center
        val last = compose.onNodeWithTag("heading.drop.${text.indexOf("* Destination")}")
            .fetchSemanticsNode().boundsInRoot
        val end = Offset(last.right - 2f, last.bottom + 100f)
        nativeDrag(start, end, cancel = true)
        assertEquals(text, repository.text(model.editor.value!!.file))
        assertEquals(0, closes)
        nativeDrag(start, end)
        val expected = "* Destination\n   Target body\n* Source\n   Source body\n"
        compose.waitUntil(8000) { model.editor.value?.text == expected }
        assertEquals(expected, repository.text(model.editor.value!!.file))
    }

    @Test
    fun previewDragScrollsToHeadingsOutsideTheOriginalViewport() = runBlocking {
        val text = (1..20).joinToString("") { "* Heading $it\n   Body $it\n" }
        open(text)
        showPreview()
        val start = compose.onNodeWithTag("preview.heading.title.0").fetchSemanticsNode().boundsInRoot.center
        val list = compose.onNodeWithTag("document.preview").fetchSemanticsNode().boundsInRoot
        nativeDrag(start, Offset(list.center.x, list.bottom - 2f), holdAtEnd = 3500)
        val expected = (2..20).joinToString("") { "* Heading $it\n   Body $it\n" } + "* Heading 1\n   Body 1\n"
        compose.waitUntil(8000) { model.editor.value?.text != text }
        assertEquals(expected, repository.text(model.editor.value!!.file))
        assertEquals(0, closes)
    }

    @Test
    fun fileNameLongPressMovesIntoFolderAndBackToParent() = runBlocking {
        val file = repository.create("Move me.org", "* Keep contents\n")
        repository.create("Target", directory = true)
        repository.sync(pull = false)
        val state = FilesScreenState()
        compose.setContent {
            MaterialTheme {
                gestureView = LocalView.current
                val snapshot by model.workspace.collectAsState()
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    FilesTopAppBar(model, snapshot, state, {})
                    Box(Modifier.weight(1f)) { FilesScreen(model, snapshot, state = state) }
                }
            }
        }
        compose.onNodeWithText("Move me.org").performScrollTo()
        val start = compose.onNodeWithText("Move me.org").fetchSemanticsNode().boundsInRoot.center
        val target = compose.onNodeWithText("Target", substring = false).fetchSemanticsNode().boundsInRoot.center
        nativeDrag(start, target, cancel = true)
        assertEquals("Move me.org", repository.state.value.files.single { it.id == file.id }.path)
        nativeDrag(start, target)
        compose.waitUntil(8000) { repository.state.value.files.any { it.id == file.id && it.path == "Target/Move me.org" } }
        // The isolated application does not run the production WorkManager worker.
        repository.sync(pull = false)
        compose.onNodeWithText("Target", substring = false).performTouchInput { click() }
        compose.waitUntil(8000) { state.folder == "Target" }
        compose.waitForIdle()
        compose.onNodeWithText("Move me.org").assertIsDisplayed()
        nativeDrag(
            compose.onNodeWithText("Move me.org").fetchSemanticsNode().boundsInRoot.center,
            compose.onNodeWithTag("files.parent").fetchSemanticsNode().boundsInRoot.center,
        )
        compose.waitUntil(8000) { repository.state.value.files.any { it.id == file.id && it.path == "Move me.org" } }
        assertEquals("* Keep contents\n", repository.text(repository.state.value.files.single { it.id == file.id }))
    }

    @Test
    fun folderRowCanDragItsSubtreeToAnInitiallyInvisibleFolder() = runBlocking {
        val source = repository.create("A Source", directory = true)
        val child = repository.create("A Source/Keep.org", "* Preserve child\n")
        for (i in 1..20) repository.create("B Folder $i", directory = true)
        repository.create("Z Destination", directory = true)
        repository.sync(pull = false)
        compose.setContent {
            MaterialTheme {
                gestureView = LocalView.current
                val snapshot by model.workspace.collectAsState()
                Box(Modifier.fillMaxSize().systemBarsPadding()) { FilesScreen(model, snapshot) }
            }
        }
        compose.onNodeWithText("A Source", substring = false).performScrollTo()
        val start = compose.onNodeWithText("A Source", substring = false).fetchSemanticsNode().boundsInRoot.center
        val viewport = compose.onNodeWithTag("files.browser").fetchSemanticsNode().boundsInRoot
        nativeDrag(start, Offset(viewport.center.x, viewport.bottom - 2f), holdAtEnd = 4000) {
            compose.onNodeWithText("Z Destination", substring = false).fetchSemanticsNode().boundsInRoot.center
        }
        compose.waitUntil(8000) {
            repository.state.value.error != null ||
                repository.state.value.files.any { it.id == source.id && it.path != "A Source" }
        }
        assertNull(repository.state.value.error)
        assertEquals("Z Destination/A Source", repository.state.value.files.single { it.id == source.id }.path)
        assertEquals("Z Destination/A Source/Keep.org", repository.state.value.files.single { it.id == child.id }.path)
        assertEquals("* Preserve child\n", repository.text(repository.state.value.files.single { it.id == child.id }))
    }

    @Test
    fun longPressingTitleMovesTheWholeSubtreeWithoutNavigating() {
        showOutline()
        compose.onNodeWithTag("heading.drag.0").assertDoesNotExist()
        compose.onNodeWithTag("heading.actions.0").assertDoesNotExist()
        val destination = model.editor.value!!.document!!.headings.last()
        val start = compose.onNodeWithTag("outline.heading.0").fetchSemanticsNode().boundsInRoot.center
        val target =
            compose.onNodeWithTag("heading.drop.${destination.start}")
                .fetchSemanticsNode().boundsInRoot
        nativeDrag(start, Offset(target.center.x, target.bottom - target.height * 0.1f))
        val expected = "* Destination\n* TODO Source\n** Child\n*** Leaf\n"
        compose.waitUntil(8000) { model.editor.value?.text == expected }
        assertEquals(expected, runBlocking { repository.text(model.editor.value!!.file) })
        compose.runOnIdle {
            assertEquals(0, closes)
            assertEquals(expected, model.editor.value!!.document!!.text)
        }
        compose.onNodeWithTag("outline.toggle.${"* Destination\n".length}").performClick()
        compose.onNodeWithText("Child").assertIsDisplayed()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(realApp.getExternalFilesDir(null), "outline-long-press.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    @Test
    fun welcomeDocumentCanReparentAndPromoteNestedTasks() = runBlocking {
        val welcome = realApp.assets.open("Welcome.org").bufferedReader().use { it.readText() }
            .replace("** NEXT", "*** NEXT")
        open(welcome)
        showOutline(inDocument = true)
        fun heading(title: String) = model.editor.value!!.document!!.headings.single { it.title.endsWith(title) }
        compose.onNodeWithTag("outline.toggle.${heading("Inbox").start}").performClick()
        compose.onNodeWithTag("outline.toggle.${heading("Try editing an Org heading").start}").performClick()
        val task = heading("Review today's agenda")
        val projects = heading("Projects")
        nativeDrag(
            compose.onNodeWithTag("outline.heading.${task.start}").fetchSemanticsNode().boundsInRoot.center,
            compose.onNodeWithTag("outline.heading.${projects.start}").fetchSemanticsNode().boundsInRoot.center,
        )
        compose.waitUntil(8000) {
            heading("Review today's agenda").level == 2 || repository.state.value.error != null
        }
        assertNull(repository.state.value.error)
        assertEquals(listOf("Projects"), heading("Review today's agenda").ancestors)
        val moved = heading("Review today's agenda")
        val parent = heading("Projects")
        val target = compose.onNodeWithTag("outline.heading.${parent.start}").fetchSemanticsNode().boundsInRoot
        nativeDrag(
            compose.onNodeWithTag("outline.heading.${moved.start}").fetchSemanticsNode().boundsInRoot.center,
            Offset(target.center.x, target.top + 2f),
        )
        compose.waitUntil(8000) { heading("Review today's agenda").level == 1 }
        assertTrue(heading("Review today's agenda").start < heading("Projects").start)
        assertEquals(model.editor.value!!.text, repository.text(model.editor.value!!.file))
    }

    @Test
    fun theIndentationGutterAcceptsDrops() {
        showOutline()
        val destination = model.editor.value!!.document!!.headings.last()
        val start = compose.onNodeWithTag("outline.heading.${destination.start}")
            .fetchSemanticsNode().boundsInRoot.center
        val first = compose.onNodeWithTag("heading.drop.0").fetchSemanticsNode().boundsInRoot
        nativeDrag(start, Offset(first.left + 2f, first.top + 2f))
        compose.waitUntil(8000) {
            model.editor.value?.text == "* Destination\n* TODO Source\n** Child\n*** Leaf\n"
        }
    }

    @Test
    fun draggingAtTheBottomScrollsAndCanMovePastInitiallyInvisibleRows() = runBlocking {
        val text = (1..20).joinToString("") { "* Heading $it\n" }
        open(text)
        showOutline()
        val start = compose.onNodeWithTag("outline.heading.0").fetchSemanticsNode().boundsInRoot.center
        val list = compose.onNodeWithTag("outline.list").fetchSemanticsNode().boundsInRoot
        nativeDrag(start, Offset(list.center.x, list.bottom - 2f), holdAtEnd = 2000)
        val expected = (2..20).joinToString("") { "* Heading $it\n" } + "* Heading 1\n"
        compose.waitUntil(8000) { model.editor.value?.text != text }
        assertEquals(expected, model.editor.value!!.text)
        assertEquals(expected, repository.text(model.editor.value!!.file))
    }

    @Test
    fun failedStructuralEditIsVisibleInsideTheOutline() = runBlocking {
        val text = "* Source\n:END:\n* Destination\n"
        open(text)
        showOutline()
        val destination = model.editor.value!!.document!!.headings.last()
        val start = compose.onNodeWithTag("outline.heading.0").fetchSemanticsNode().boundsInRoot.center
        val end = compose.onNodeWithTag("outline.heading.${destination.start}")
            .fetchSemanticsNode().boundsInRoot.center
        nativeDrag(start, end)
        compose.waitUntil(8000) {
            compose.onAllNodesWithTag("outline.moveError").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("outline.moveError").assertIsDisplayed()
        assertEquals(text, model.editor.value!!.text)
        assertEquals(text, repository.text(model.editor.value!!.file))
    }

    @Test
    fun droppingBelowTheLastRowStillReorders() {
        showOutline()
        val destination = model.editor.value!!.document!!.headings.last()
        val start = compose.onNodeWithTag("outline.heading.0").fetchSemanticsNode().boundsInRoot.center
        val last = compose.onNodeWithTag("heading.drop.${destination.start}")
            .fetchSemanticsNode().boundsInRoot
        nativeDrag(start, Offset(last.center.x, last.bottom + 40f))
        compose.waitUntil(8000) {
            model.editor.value?.text == "* Destination\n* TODO Source\n** Child\n*** Leaf\n"
        }
    }

    @Test
    fun repeatedDragsInDocumentCanIndentAndPromoteAgain() {
        showOutline(inDocument = true)
        val destination = model.editor.value!!.document!!.headings.last()
        val start = compose.onNodeWithTag("outline.heading.0").fetchSemanticsNode().boundsInRoot.center
        val end = compose.onNodeWithTag("outline.heading.${destination.start}")
            .fetchSemanticsNode().boundsInRoot.center
        nativeDrag(start, end)
        compose.waitUntil(8000) {
            model.editor.value?.text == "* Destination\n** TODO Source\n*** Child\n**** Leaf\n"
        }
        val moved = model.editor.value!!.document!!.headings.single { it.title == "Source" }
        val nextStart = compose.onNodeWithTag("outline.heading.${moved.start}")
            .fetchSemanticsNode().boundsInRoot.center
        val nextTarget = compose.onNodeWithTag("outline.heading.0").fetchSemanticsNode().boundsInRoot
        nativeDrag(nextStart, Offset(nextTarget.center.x, nextTarget.top + 2f))
        compose.waitUntil(8000) { model.editor.value?.text == source }
        assertEquals(0, closes)
    }

    @Test
    fun droppingOnTitleCenterChangesHierarchy() {
        showOutline()
        val destination = model.editor.value!!.document!!.headings.last()
        val start = compose.onNodeWithTag("outline.heading.0").fetchSemanticsNode().boundsInRoot.center
        val end = compose.onNodeWithTag("outline.heading.${destination.start}")
            .fetchSemanticsNode().boundsInRoot.center
        nativeDrag(start, end)
        compose.waitUntil(8000) {
            model.editor.value?.text == "* Destination\n** TODO Source\n*** Child\n**** Leaf\n"
        }
    }

    @Test
    fun cancelledTitleDragLeavesTheDocumentAndNavigationUnchanged() {
        showOutline()
        val destination = model.editor.value!!.document!!.headings.last()
        val start = compose.onNodeWithTag("outline.heading.0").fetchSemanticsNode().boundsInRoot.center
        val end =
            compose.onNodeWithTag("heading.drop.${destination.start}")
                .fetchSemanticsNode().boundsInRoot.center
        nativeDrag(start, end, cancel = true)
        compose.runOnIdle {
            assertEquals(source, model.editor.value!!.text)
            assertEquals(0, closes)
        }
    }

    @Test
    fun titleTapStillNavigatesAndAccessibilityActionsReplaceTheMenu() {
        showOutline()
        compose.onNodeWithTag("heading.actions.0").assertDoesNotExist()
        val actions = compose.onNodeWithTag("outline.heading.0")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertTrue(actions.single { it.label == realApp.label("down") }.action())
        }
        compose.waitUntil(8000) { model.editor.value?.text?.startsWith("* Destination\n") == true }
        compose.runOnIdle { assertEquals(0, closes) }
        val moved = model.editor.value!!.document!!.headings.single { it.title == "Source" }
        compose.onNodeWithTag("outline.heading.${moved.start}").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, closes)
            assertEquals(moved.start, model.editor.value!!.jumpByte)
        }
    }

    @Test
    fun scrollingFromTitleDoesNotReorderOrNavigate() = runBlocking {
        val text = (1..40).joinToString("") { "* Heading $it\n" }
        open(text)
        showOutline()
        compose.onNodeWithTag("outline.list").performTouchInput { swipeUp(durationMillis = 200) }
        compose.onNodeWithText("Heading 1").assertIsNotDisplayed()
        compose.runOnIdle {
            assertEquals(text, model.editor.value!!.text)
            assertEquals(0, closes)
        }
    }

    private fun nativeDrag(
        start: Offset,
        end: Offset,
        cancel: Boolean = false,
        holdAtEnd: Long = 0,
        releaseAt: (() -> Offset)? = null,
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val screen = IntArray(2)
        gestureView?.let { view -> compose.waitUntil(3000) { view.hasWindowFocus() } }
        compose.runOnIdle {
            (gestureView ?: WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() })
                .getLocationOnScreen(screen)
        }
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, point: Offset) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action,
                point.x + screen[0], point.y + screen[1], 0,
            )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, start)
        SystemClock.sleep(650)
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        for (step in 1..20) {
            send(MotionEvent.ACTION_MOVE, start + (end - start) * (step / 20f))
            SystemClock.sleep(16)
        }
        repeat((holdAtEnd / 16).toInt()) {
            compose.mainClock.advanceTimeBy(16)
            SystemClock.sleep(16)
        }
        val release = try { releaseAt?.invoke() ?: end } catch (failure: Throwable) {
            send(MotionEvent.ACTION_CANCEL, end)
            throw failure
        }
        if (release != end) send(MotionEvent.ACTION_MOVE, release)
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, release)
        compose.waitForIdle()
        SystemClock.sleep(250)
    }
}
