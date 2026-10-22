package com.roifewu.orgenda.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.core.NativeOrgEngine
import com.roifewu.orgenda.data.WorkspaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AgendaBehaviorParityTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var perspective by mutableStateOf("overview")
    private fun snapshot(text: String): WorkspaceSnapshot {
        val document = NativeOrgEngine().use { it.parse("inbox.org", text) }
        return WorkspaceSnapshot(documents = mapOf("inbox.org" to document), revision = 1)
    }
    private fun showAgenda(data: WorkspaceSnapshot) {
        compose.setContent { MaterialTheme { AgendaScreen(data, {}, {}, {}, {}, perspective, { perspective = it }, {}, {}) } }
        compose.waitForIdle()
    }
    @OptIn(ExperimentalMaterial3Api::class)
    private fun chooseDate(day: LocalDate) {
        val millis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        // Material replaces the visible day numeral with its full localized date in semantics.
        val label = checkNotNull(DatePickerDefaults.dateFormatter().formatDate(millis, locale, true))
        compose.onNode(hasText(label, substring = true) and hasClickAction()).performScrollTo().performClick()
    }

    @Test fun overdueOnlyDashboardStillExplainsItsEmptyDateScope() {
        showAgenda(snapshot("* TODO Past task\nSCHEDULED: <${LocalDate.now().minusDays(1)}>\n"))
        compose.onNodeWithTag("agenda.list.overview").performScrollToNode(hasText(context.label("next_seven_days")))
        compose.onNodeWithText(context.label("next_seven_days")).assertIsDisplayed()
        compose.onNodeWithTag("agenda.list.overview").performScrollToNode(hasText(context.label("nothing_next_seven_days")))
        compose.onNodeWithText(context.label("nothing_next_seven_days")).assertIsDisplayed()
    }

    @Test fun eachPerspectiveRestoresItsOwnScrollPosition() {
        perspective = "unscheduled"
        showAgenda(snapshot((1..80).joinToString("") { "* TODO Task $it\n" }))
        compose.onNodeWithTag("agenda.list.unscheduled").performScrollToNode(hasText("Task 60"))
        compose.onNodeWithText("Task 60").assertIsDisplayed()
        compose.runOnIdle { perspective = "overdue" }
        compose.waitForIdle()
        compose.runOnIdle { perspective = "unscheduled" }
        compose.waitForIdle()
        compose.onNodeWithText("Task 60").assertIsDisplayed()
    }

    @Test fun removedWorkflowPerspectiveReturnsToDashboard() {
        perspective = "REMOVED_STATE"
        showAgenda(snapshot("* TODO Still here\n"))
        compose.runOnIdle { assertEquals("overview", perspective) }
        compose.onNodeWithText("Still here").assertIsDisplayed()
    }

    @Test fun calendarRescheduleRetainsDraftAfterStateRestoration() {
        val selected = LocalDate.of(2026, 10, 22)
        val data = snapshot("* TODO Move this task\nSCHEDULED: <$selected>\n")
        var scheduled: LocalDate? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme {
            CalendarScreen(data, selected, {}, {}, {}, {}, { _, _ -> }, {}, {}) { _, date -> scheduled = date }
        } }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Move this task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Move this task").performTouchInput { longClick() }
        compose.onNodeWithText(context.label("reschedule")).performScrollTo().performClick()
        chooseDate(LocalDate.of(2026, 10, 26))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(context.label("save")).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 10, 26), scheduled) }
    }

    @Test fun calendarDatePickerRetainsSelectionAfterStateRestoration() {
        var selected by mutableStateOf(LocalDate.of(2026, 10, 22))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme {
            CalendarScreen(WorkspaceSnapshot(), selected, { selected = it }, {}, {}, {}, { _, _ -> }, {}, {}) { _, _ -> }
        } }
        compose.onNodeWithTag("calendar.datePicker").performClick()
        chooseDate(LocalDate.of(2026, 10, 26))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(context.label("save")).performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 10, 26), selected) }
    }

    @Test fun calendarRestoresPositionWithinALargeDay() {
        val selected = LocalDate.of(2026, 10, 22)
        val data = snapshot((1..100).joinToString("") { "* TODO Task $it\nSCHEDULED: <$selected>\n" })
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme {
            CalendarScreen(data, selected, {}, {}, {}, {}, { _, _ -> }, {}, {}) { _, _ -> }
        } }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Task 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("calendar.timeline").performScrollToNode(hasText("Task 60"))
        compose.onNodeWithTag("calendar.timeline").performTouchInput { swipeUp() }
        compose.waitForIdle()
        fun scrollPosition() = compose.onNodeWithTag("calendar.timeline").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        val previousPosition = scrollPosition()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(15000) { compose.onAllNodes(hasText("Task ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals(previousPosition, scrollPosition(), 0.01f)
        compose.onNodeWithText("Task 1").assertDoesNotExist()
    }
}
