package com.roifewu.orgenda.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.WorkspaceSnapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CalendarInteractionTest {
    @get:Rule val compose = createComposeRule()
    private var selected by mutableStateOf(LocalDate.of(2026, 10, 22))
    private fun show(snapshot: WorkspaceSnapshot = WorkspaceSnapshot()) {
        compose.setContent { MaterialTheme { CalendarScreen(snapshot, selected, { selected = it }, {}, {}, {}, { _, _ -> }, {}, {}) { _, _ -> } } }
        compose.waitForIdle()
    }
    @Test fun verticalWeekSwipeKeepsWeekdayAndTimelineFollows() {
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
    @Test fun densityHandleExpandsAndYearHasTwelveMonthGrids() {
        show()
        compose.onNodeWithTag("calendar.densityHandle").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 120f)); up() }
        compose.waitForIdle()
        compose.onNodeWithTag("calendar.day.2026-10-01").assertExists()
        compose.onNodeWithTag("calendar.densityHandle").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 120f)); up() }
        compose.waitForIdle()
        compose.onNodeWithTag("calendar.month.2026-01").assertExists()
        compose.onNodeWithTag("calendar.month.2026-12").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 12, 1), selected) }
    }
    @Test fun thousandsOfSameDayTasksStayLazyAndDoNotSelectAnotherDayOnScroll() {
        val document = NativeOrgEngine().use { engine -> engine.parse("inbox.org", (1..2000).joinToString("") { "* TODO Task $it\nSCHEDULED: <2026-10-22 Thu>\n" }) }
        show(WorkspaceSnapshot(documents = mapOf("inbox.org" to document), revision = 1))
        compose.waitUntil(15000) { compose.onAllNodesWithText("Task 1", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Task 2000").assertDoesNotExist()
        compose.onNodeWithTag("calendar.timeline").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 10, 22), selected) }
    }

}
