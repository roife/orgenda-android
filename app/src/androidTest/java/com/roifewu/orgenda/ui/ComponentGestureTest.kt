package com.roifewu.orgenda.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.FileEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComponentGestureTest {
    @get:Rule val compose = createComposeRule()
    private val file = FileEntity("gesture-file", "gesture-workspace", "gesture.org")

    private fun task() =
        NativeOrgEngine().use { it.parse("gesture.org", "* TODO Gesture task\n").headings.single() }

    @Test
    fun fileSwipeRevealsMoveDeleteAndPinWithoutInvokingThem() {
        var pins = 0
        var moves = 0
        var deletes = 0
        compose.setContent {
            MaterialTheme {
                FileSwipeActions(file, { pins++ }, { moves++ }, { deletes++ }) {
                    ListItem(headlineContent = { Text(file.path) })
                }
            }
        }
        val row = compose.onNodeWithTag("files.row.${file.path}")
        row.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("files.swipe.move").assertIsDisplayed()
        compose.onNodeWithTag("files.swipe.delete").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, moves + pins + deletes) }
        compose.onNodeWithTag("files.swipe.move").performClick()
        row.performTouchInput { swipeRight() }
        compose.onNodeWithTag("files.swipe.pin").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, pins) }
        compose.onNodeWithTag("files.swipe.pin").performClick()
        row.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("files.swipe.delete").performClick()
        compose.runOnIdle {
            assertEquals(1, moves)
            assertEquals(1, pins)
            assertEquals(1, deletes)
        }
    }

    @Test
    fun taskTrailingSwipeOffersBothEditAndRescheduleWithoutFullSwipeActions() {
        val item = task()
        var opens = 0
        var schedules = 0
        var completions = 0
        compose.setContent {
            MaterialTheme {
                TaskRow(item, { opens++ }, { completions++ }, onReschedule = { schedules++ })
            }
        }
        val row = compose.onNodeWithTag("agenda.row.${item.id}")
        row.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("agenda.swipe.edit").assertIsDisplayed()
        compose.onNodeWithTag("agenda.swipe.reschedule").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, opens + schedules + completions) }
        compose.onNodeWithTag("agenda.swipe.reschedule").performClick()
        row.performTouchInput { swipeLeft() }
        compose.onNodeWithTag("agenda.swipe.edit").performClick()
        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, schedules)
            assertEquals(0, completions)
        }
    }

    @Test
    fun fullLeadingSwipeCompletesOnceAndUsesTheCurrentCallback() {
        val item = task()
        var increment by mutableIntStateOf(1)
        var completions = 0
        compose.setContent {
            MaterialTheme {
                val currentIncrement = increment
                TaskRow(item, {}, { completions += currentIncrement })
            }
        }
        val row = compose.onNodeWithTag("agenda.row.${item.id}")
        row.performTouchInput { swipeRight(durationMillis = 600) }
        compose.runOnIdle {
            assertEquals(1, completions)
            increment = 10
        }
        row.performTouchInput { swipeRight(durationMillis = 600) }
        compose.runOnIdle { assertEquals(11, completions) }
    }

    @Test
    fun aShortTaskSwipeExposesCompletionAndCancellationNeverCompletes() {
        val item = task()
        var completions = 0
        compose.setContent { MaterialTheme { TaskRow(item, {}, { completions++ }) } }
        val row = compose.onNodeWithTag("agenda.row.${item.id}")
        row.performTouchInput {
            swipe(Offset(width * 0.4f, centerY), Offset(width * 0.65f, centerY), 600)
        }
        compose.onNodeWithTag("agenda.swipe.complete").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, completions) }
        compose.onNodeWithTag("agenda.swipe.complete").performClick()
        row.performTouchInput {
            down(Offset(width * 0.05f, centerY))
            moveTo(Offset(width * 0.95f, centerY), 100)
            cancel()
        }
        compose.runOnIdle { assertEquals(1, completions) }
    }

    @Test
    fun openingAnotherRowClosesThePreviousRowAndVerticalScrollingDoesNotAct() {
        var actions = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalSwipeActionsCoordinator provides remember { SwipeActionsCoordinator() }
                ) {
                    LazyColumn(Modifier.height(320.dp).testTag("gesture.list")) {
                        items(20) { index ->
                            FileSwipeActions(
                                file.copy(id = "$index", path = "$index.org"),
                                { actions++ },
                                { actions++ },
                                { actions++ },
                            ) {
                                ListItem(headlineContent = { Text("File $index") })
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("files.row.0.org").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("files.row.1.org").performTouchInput { swipeLeft() }
        compose.onAllNodesWithTag("files.swipe.move").assertCountEquals(1)
        compose.onNodeWithTag("gesture.list").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0, actions) }
        compose.onNodeWithText("File 0").assertDoesNotExist()
    }

    @Test
    fun rightToLeftLayoutReversesTheSwipeEdges() {
        var pins = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    FileSwipeActions(file, { pins++ }, {}, {}) {
                        ListItem(headlineContent = { Text(file.path) })
                    }
                }
            }
        }
        compose.onNodeWithTag("files.row.${file.path}").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("files.swipe.pin").performClick()
        compose.runOnIdle { assertEquals(1, pins) }
        compose.onNodeWithTag("files.row.${file.path}").performTouchInput { swipeRight() }
        compose.onNodeWithTag("files.swipe.move").assertIsDisplayed()
        compose.onNodeWithTag("files.swipe.delete").assertIsDisplayed()
    }

    @Test
    fun largeTextKeepsFileActionLabelsInsideTheRow() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, 2f)
                ) {
                    FileSwipeActions(file, {}, {}, {}) {
                        ListItem(headlineContent = { Text(file.path) })
                    }
                }
            }
        }
        compose.onNodeWithTag("files.row.${file.path}").performTouchInput { swipeLeft() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (key in listOf("swipe_move", "swipe_delete")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithText(context.label(key), useUnmergedTree = true)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("$key line is clipped vertically", layout.didOverflowHeight)
            assertFalse("$key label is truncated", layout.isLineEllipsized(0))
        }
    }

    @Test
    fun headingDropsRejectDescendantsStaleDocumentsAndOtherSessions() {
        NativeOrgEngine().use { engine ->
            val document = engine.parse("gesture.org", "* Parent\n** Child\n* Destination\n")
            val parent = document.headings[0]
            val child = document.headings[1]
            val target = document.headings[2]
            val drag = HeadingDrag(parent, 7)
            assertFalse(acceptsHeadingDrop(drag, parent, 7))
            assertFalse(acceptsHeadingDrop(drag, child, 7))
            assertFalse(acceptsHeadingDrop(drag, target, 8))
            assertFalse(acceptsHeadingDrop(drag, target.copy(path = "other.org"), 7))
            assertFalse(acceptsHeadingDrop(drag, target.copy(sourceHash = "changed"), 7))
            assertTrue(acceptsHeadingDrop(drag, target, 7))
            assertEquals(HeadingPlacement.BEFORE, headingDropPlacement(10f, 100f))
            assertEquals(HeadingPlacement.CHILD, headingDropPlacement(50f, 100f))
            assertEquals(HeadingPlacement.AFTER, headingDropPlacement(90f, 100f))
        }
    }

    @Test
    fun nativeTaskCompletionControlCanDragWithoutTogglingTheTask() {
        val item = task()
        var toggles = 0
        var drops = 0
        lateinit var view: View
        compose.setContent {
            MaterialTheme {
                view = LocalView.current
                Column(Modifier.fillMaxSize().padding(top = 64.dp)) {
                    TaskRow(item, {}, { toggles++ }, draggable = true, allowsSwipe = false)
                    Spacer(Modifier.height(80.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .height(100.dp)
                            .testTag("gesture.taskTarget")
                            .orgDrop("task") { payload ->
                                assertEquals(item, payload)
                                drops++
                            }
                    ) {
                        Text("Date drop target")
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source =
            compose
                .onNodeWithContentDescription(context.label("mark_complete") + ": " + item.title)
                .fetchSemanticsNode()
                .boundsInRoot
                .center
        val target =
            compose.onNodeWithTag("gesture.taskTarget").fetchSemanticsNode().boundsInRoot.center
        val screen = IntArray(2)
        compose.runOnIdle { view.getLocationOnScreen(screen) }
        nativeDrag(source, target, screen)
        compose.runOnIdle {
            assertEquals(1, drops)
            assertEquals(0, toggles)
        }
        compose
            .onNodeWithContentDescription(context.label("mark_complete") + ": " + item.title)
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, toggles) }
    }

    @Test
    fun calendarTaskTitleCanDragWithoutOpeningPreviewAndStillRespondsToTap() {
        val item = task()
        var opens = 0
        var drops = 0
        lateinit var view: View
        compose.setContent {
            MaterialTheme {
                view = LocalView.current
                Column(Modifier.fillMaxSize().padding(top = 64.dp)) {
                    TaskRow(item, { opens++ }, {}, draggable = true, allowsSwipe = false)
                    Spacer(Modifier.height(80.dp))
                    Box(Modifier.fillMaxWidth().height(100.dp).testTag("gesture.date")
                        .orgDrop("task") { assertEquals(item, it); drops++ }) {
                        Text("Date")
                    }
                }
            }
        }
        val source = compose.onNodeWithTag("agenda.item.title.${item.id}", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center
        val target = compose.onNodeWithTag("gesture.date").fetchSemanticsNode().boundsInRoot.center
        val screen = IntArray(2)
        compose.runOnIdle { view.getLocationOnScreen(screen) }
        nativeDrag(source, target, screen, cancel = true)
        compose.runOnIdle { assertEquals(0, drops); assertEquals(0, opens) }
        nativeDrag(source, target, screen)
        compose.runOnIdle { assertEquals(1, drops); assertEquals(0, opens) }
        compose.onNodeWithTag("agenda.item.title.${item.id}", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Test
    fun nativeDragHandleDoesNotOpenItsRowsLongPressMenu() {
        var menus = 0
        var drops = 0
        lateinit var view: View
        compose.setContent {
            MaterialTheme {
                view = LocalView.current
                Column(Modifier.fillMaxSize().padding(top = 64.dp)) {
                    Box(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = {}, onLongClick = { menus++ })
                    ) {
                        Box(
                            Modifier.size(64.dp).testTag("gesture.fileSource").orgDrag("file", file)
                        ) {
                            Text("Drag")
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth()
                            .height(100.dp)
                            .testTag("gesture.fileTarget")
                            .orgDrop("file") { drops++ }
                    ) {
                        Text("Folder")
                    }
                }
            }
        }
        val source =
            compose
                .onNodeWithTag("gesture.fileSource", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
                .center
        val target =
            compose.onNodeWithTag("gesture.fileTarget").fetchSemanticsNode().boundsInRoot.center
        val screen = IntArray(2)
        compose.runOnIdle { view.getLocationOnScreen(screen) }
        nativeDrag(source, target, screen)
        compose.runOnIdle {
            assertEquals(1, drops)
            assertEquals(0, menus)
        }
    }

    private fun nativeDrag(
        start: Offset,
        end: Offset,
        screen: IntArray,
        cancel: Boolean = false,
        beforeDrop: () -> Unit = {},
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, point: Offset) {
            val event =
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    action,
                    point.x + screen[0],
                    point.y + screen[1],
                    0,
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
        beforeDrop()
        compose.waitForIdle()
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end)
        compose.waitForIdle()
        SystemClock.sleep(250)
    }
}
