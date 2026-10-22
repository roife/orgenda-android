@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.roifewu.orgenda.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.WorkspaceSnapshot
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarInteractionTest {
    @get:Rule val compose = createComposeRule()
    private var selected by mutableStateOf(LocalDate.of(2026, 10, 22))
    private var pixelsPerDp = 1f
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun density(mode: String) =
        compose
            .onNodeWithTag("calendar.densityHandle")
            .performCustomAccessibilityActionWithLabel(context.label(mode))

    private fun assertDensity(mode: String) =
        compose
            .onNodeWithTag("calendar.densityHandle")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.label(mode),
                )
            )

    private fun show(
        snapshot: WorkspaceSnapshot = WorkspaceSnapshot(),
        fontScale: Float =
            InstrumentationRegistry.getArguments().getString("calendarFontScale")?.toFloat() ?: 1f,
    ) {
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(pixelsPerDp, fontScale)) {
                MaterialTheme {
                    CalendarScreen(
                        snapshot,
                        selected,
                        { selected = it },
                        {},
                        {},
                        {},
                        { _, _ -> },
                        {},
                        {},
                    ) { _, _ ->
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun verticalWeekSwipeKeepsWeekdayAndTimelineFollows() {
        show()
        compose.onNodeWithTag("calendar.pager").performTouchInput { swipeUp(durationMillis = 250) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 10, 29), selected) }
        compose.onNodeWithTag("calendar.day.2026-10-30").performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 10, 30), selected) }
        compose.onNodeWithTag("calendar.timeline").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(selected > LocalDate.of(2026, 10, 30)) }
    }

    @Test
    fun densityHandleExpandsAndYearHasTwelveMonthGrids() {
        show()
        dragHandle(190f)
        compose.waitForIdle()
        compose.onNodeWithTag("calendar.day.2026-10-01").assertExists()
        dragHandle(110f)
        compose.waitForIdle()
        compose.onNodeWithTag("calendar.month.2026-01").assertExists()
        compose.onNodeWithTag("calendar.month.2026-12").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 12, 1), selected) }
    }

    private fun dragHandle(distanceDp: Float) {
        val start =
            compose.onNodeWithTag("calendar.densityHandle").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(start)
            for (step in 1..12) moveTo(
                start + Offset(0f, distanceDp * pixelsPerDp * step / 12),
                delayMillis = 16,
            )
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun shortDragReturnsToWeekAndBoundaryDragLeavesCalendarInteractive() {
        show()
        dragHandle(50f)
        assertDensity("week")
        compose.onNodeWithTag("calendar.day.2026-10-23").performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 10, 23), selected) }
        dragHandle(500f)
        assertDensity("year")
        compose.onNodeWithTag("calendar.month.2026-02").performClick()
        compose.onNodeWithTag("calendar.day.2026-02-12").performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 2, 12), selected) }
    }

    @Test
    fun handleTracksFingerAndSettlesFromItsReleasePosition() {
        show()
        val start =
            compose.onNodeWithTag("calendar.densityHandle").fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput {
            down(start)
            moveTo(start + Offset(0f, 20f * pixelsPerDp))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onRoot().performTouchInput { moveTo(start + Offset(0f, 200f * pixelsPerDp)) }
        compose.mainClock.advanceTimeByFrame()
        val during =
            compose
                .onNodeWithTag("calendar.densityHandle")
                .fetchSemanticsNode()
                .boundsInRoot
                .center
                .y
        assertEquals(180f, (during - start.y) / pixelsPerDp, 12f)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        val released =
            compose
                .onNodeWithTag("calendar.densityHandle")
                .fetchSemanticsNode()
                .boundsInRoot
                .center
                .y
        assertEquals(during / pixelsPerDp, released / pixelsPerDp, 8f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertDensity("month")
        compose.onNodeWithTag("calendar.day.2026-10-23").performClick()
    }

    @Test
    fun densityActionsAnimateAndCanReverseBeforeCompletion() {
        show()
        val start =
            compose
                .onNodeWithTag("calendar.densityHandle")
                .fetchSemanticsNode()
                .boundsInRoot
                .center
                .y
        compose.mainClock.autoAdvance = false
        density("year")
        compose.mainClock.advanceTimeBy(80)
        val expanding =
            compose
                .onNodeWithTag("calendar.densityHandle")
                .fetchSemanticsNode()
                .boundsInRoot
                .center
                .y
        assertTrue(expanding > start + 10f * pixelsPerDp)
        assertTrue(expanding < start + 396f * pixelsPerDp)
        density("week")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertDensity("week")
        compose.onNodeWithTag("calendar.day.2026-10-23").performClick()
    }

    @Test
    fun browsingAnotherYearMorphsTheVisibleMonthWithoutChangingSelection() {
        show()
        density("year")
        compose.onNodeWithTag("calendar.pager").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 10, 22), selected) }
        compose.onNodeWithTag("calendar.month.2027-10").assertExists()
        compose.onNodeWithTag("calendar.month.2027-12").performClick()
        compose.onNodeWithTag("calendar.day.2027-12-01").assertIsSelected()
        density("week")
        compose.onNodeWithTag("calendar.day.2027-12-01").assertIsSelected()
    }

    @Test
    fun largeTextKeepsBothStripStopsReachable() {
        show(fontScale = 2f)
        dragHandle(80f)
        assertDensity("month")
        compose.onNodeWithTag("calendar.day.2026-10-22").assertIsDisplayed()
        density("year")
        compose.onNodeWithTag("calendar.month.2026-12").performClick()
        compose.onNodeWithTag("calendar.day.2026-12-01").assertIsSelected()
    }

    @Test
    fun thousandsOfSameDayTasksStayLazyAndDoNotSelectAnotherDayOnScroll() {
        val document =
            NativeOrgEngine().use { engine ->
                engine.parse(
                    "inbox.org",
                    (1..2000).joinToString("") { "* TODO Task $it\nSCHEDULED: <2026-10-22 Thu>\n" },
                )
            }
        show(WorkspaceSnapshot(documents = mapOf("inbox.org" to document), revision = 1))
        compose.waitUntil(15000) {
            compose
                .onAllNodesWithText("Task 1", substring = false)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Task 2000").assertDoesNotExist()
        compose.onNodeWithTag("calendar.timeline").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 10, 22), selected) }
    }
}
