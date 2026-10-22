package com.roifewu.orgenda.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.core.NativeOrgEngine
import com.roifewu.orgenda.data.WorkspaceSnapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@RunWith(AndroidJUnit4::class)
class CurrentDayBehaviorTest {
    @get:Rule val compose = createComposeRule()
    private val initial = LocalDate.of(2026, 10, 22)
    private var today by mutableStateOf(TodayState(initial, ZoneId.of("Asia/Taipei")))
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun snapshot(text: String): WorkspaceSnapshot {
        val document = NativeOrgEngine().use { it.parse("inbox.org", text) }
        return WorkspaceSnapshot(documents = mapOf("inbox.org" to document), revision = 1)
    }
    private fun agenda(data: WorkspaceSnapshot, perspective: String) {
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalToday provides today) {
            AgendaScreen(data, {}, {}, {}, {}, perspective, {}, {}, {})
        } } }
    }

    @Test fun overdueTasksRefreshOnDayChangeWithoutAWorkspaceRevision() {
        agenda(snapshot("* TODO Due today\nSCHEDULED: <$initial>\n"), "overdue")
        compose.onNodeWithText(context.label("no_tasks")).assertIsDisplayed()
        compose.runOnIdle { today = today.copy(date = initial.plusDays(1)) }
        compose.onNodeWithText("Due today").assertIsDisplayed()
    }

    @Test fun dashboardMovesItsSevenDayWindowWithoutAWorkspaceRevision() {
        agenda(snapshot("* TODO Newly in range\nSCHEDULED: <${initial.plusDays(7)}>\n"), "overview")
        compose.onNodeWithText(context.label("no_tasks")).assertIsDisplayed()
        compose.runOnIdle { today = today.copy(date = initial.plusDays(1)) }
        compose.onNodeWithTag("agenda.list.overview").performScrollToNode(hasText("Newly in range"))
        compose.onNodeWithText("Newly in range").assertIsDisplayed()
    }

    @Test fun calendarUpdatesTodayAndOverdueRowsWithoutChangingTheSelectedDate() {
        val data = snapshot("* TODO Due today\nSCHEDULED: <$initial>\n")
        var selected by mutableStateOf(initial)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalToday provides today) {
            CalendarScreen(data, selected, { selected = it }, {}, {}, {}, { _, _ -> }, {}, {}) { _, _ -> }
        } } }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Due today").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { today = today.copy(date = initial.plusDays(1)) }
        compose.runOnIdle { assertEquals(initial, selected) }
        compose.onNodeWithTag("calendar.day.${initial.plusDays(1)}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.label("today")))
        compose.onNodeWithTag("calendar.day.${initial.plusDays(1)}").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText(context.label("overdue") + " · 1").fetchSemanticsNodes().isNotEmpty() }
        // Adding today's overdue action changes all following section offsets.
        val target = initial.plusDays(2)
        compose.onNodeWithTag("calendar.day.$target").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(target.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))).assertIsDisplayed()
        // Compare the section surface, not its date Text inset by the header's vertical padding.
        val header = compose.onNodeWithTag("calendar.header.$target").fetchSemanticsNode().boundsInRoot
        val timeline = compose.onNodeWithTag("calendar.timeline").fetchSemanticsNode().boundsInRoot
        assertEquals(timeline.top, header.top, 2f)
    }
}
