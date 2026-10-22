package com.roifewu.orgenda.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.WorkspaceSnapshot
import com.roifewu.orgenda.data.WorkspaceEntity
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsBehaviorTest {
    @get:Rule val compose = createComposeRule()
    private fun label(key: String) = InstrumentationRegistry.getInstrumentation().targetContext.label(key)
    private fun awaitSaveState(condition: () -> Boolean) {
        // Clicking schedules LaunchedEffect; UI idleness alone is not the persistence completion signal.
        compose.waitUntil(timeoutMillis = 5_000) { compose.runOnIdle(condition) }
    }
    private fun clickEditorAction(key: String) {
        // Settle the IME before scrolling so the focused field cannot bring the action off screen.
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText(label(key)).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
    }

    @Test fun editingTemplateDoesNotPublishUntilSave() {
        val baseline = WorkspaceConfig.defaults()
        var saved: WorkspaceConfig? = null
        var closed = false
        compose.setContent { MaterialTheme {
            CaptureTemplateEditor(baseline, WorkspaceSnapshot(), baseline.capture.templates.first(), { closed = true }, {}, { _, _, value -> saved = value })
        } }
        compose.onNode(hasSetTextAction() and hasText(label("name"))).performTextReplacement("Inbox revised")
        compose.runOnIdle { assertNull(saved); assertFalse(closed) }
        clickEditorAction("save")
        awaitSaveState { saved != null && closed }
        compose.runOnIdle { assertEquals("Inbox revised", saved?.capture?.templates?.first()?.name); assertTrue(closed) }
    }

    @Test fun cancellingTemplateOffersDiscardWithoutPublishing() {
        val baseline = WorkspaceConfig.defaults()
        var saved: WorkspaceConfig? = null
        var closed = false
        compose.setContent { MaterialTheme {
            CaptureTemplateEditor(baseline, WorkspaceSnapshot(), baseline.capture.templates.first(), { closed = true }, {}, { _, _, value -> saved = value })
        } }
        compose.onNode(hasSetTextAction() and hasText(label("name"))).performTextReplacement("Uncommitted template")
        clickEditorAction("cancel")
        compose.onNodeWithText(label("discard_changes")).assertIsDisplayed()
        compose.runOnIdle { assertNull(saved); assertFalse(closed) }
        compose.onNodeWithText(label("discard")).performClick()
        compose.runOnIdle { assertNull(saved); assertTrue(closed) }
    }

    @Test fun deletingDefaultStateRequiresConfirmingReplacementActions() {
        var config by mutableStateOf(WorkspaceConfig.defaults().copy(workflow = Workflow(listOf(WorkflowSequence(process = listOf("TODO", "NEXT"))))))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { WorkflowSettings(config, "settings-test", { _, _, value -> config = value }) { config = it } } } }
        compose.onNodeWithContentDescription(label("actions") + ": TODO").performClick()
        compose.onNodeWithText(label("remove")).performClick()
        compose.onNodeWithText(label("repair_workflow_defaults")).assertIsDisplayed()
        compose.runOnIdle { assertEquals("TODO", config.workflow.sequences.first().initial) }
        compose.onNodeWithText(label("confirm")).performClick()
        compose.runOnIdle {
            assertEquals(listOf("NEXT"), config.workflow.sequences.first().process)
            assertEquals("NEXT", config.workflow.sequences.first().initial)
            assertEquals("NEXT", config.workflow.sequences.first().reopen)
        }
    }

    @Test fun templateEditorAndUncommittedDraftSurviveStateRestoration() {
        val restoration = StateRestorationTester(compose)
        val baseline = WorkspaceConfig.defaults()
        var saved: WorkspaceConfig? = null
        restoration.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { CaptureSettings(baseline, WorkspaceSnapshot(), persist = { _, _, value -> saved = value }, change = { saved = it }) }
        } }
        compose.onNode(hasClickAction() and hasText("inbox.org", substring = true)).performClick()
        compose.onNode(hasSetTextAction() and hasText(label("name"))).performTextReplacement("Restored template draft")
        compose.runOnIdle { assertNull(saved) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction() and hasText(label("name"))).assert(hasText("Restored template draft"))
        compose.runOnIdle { assertNull(saved) }
        clickEditorAction("save")
        awaitSaveState { saved != null }
        compose.runOnIdle { assertEquals("Restored template draft", saved?.capture?.templates?.first()?.name) }
    }

    @Test fun failedTemplateSaveKeepsDraftAndRetriesBeforeClosing() {
        val baseline = WorkspaceConfig.defaults()
        val failureGate = CompletableDeferred<Unit>()
        val workspace = WorkspaceEntity("template-workspace", "private", "/test", "Test")
        var attempts = 0
        var saved: WorkspaceConfig? = null
        var closed = false
        compose.setContent { MaterialTheme {
            CaptureTemplateEditor(baseline, WorkspaceSnapshot(workspace = workspace), baseline.capture.templates.first(), { closed = true }, {}) { workspaceId, expected, candidate ->
                assertEquals(workspace.id, workspaceId)
                assertEquals(baseline, expected)
                attempts++
                if (attempts == 1) { failureGate.await(); error("Template save failed") }
                saved = candidate
            }
        } }
        compose.onNode(hasSetTextAction() and hasText(label("name"))).performTextReplacement("Retry template draft")
        clickEditorAction("save")
        compose.onNodeWithText(label("saving")).assertIsNotEnabled()
        compose.onNodeWithText(label("cancel")).assertIsNotEnabled()
        compose.onNodeWithText(label("name")).assertIsNotEnabled()
        awaitSaveState { attempts == 1 }
        compose.runOnIdle { assertEquals(1, attempts); assertNull(saved); assertFalse(closed); failureGate.complete(Unit) }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Template save failed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Template save failed").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText(label("name"))).assert(hasText("Retry template draft"))
        compose.runOnIdle { assertNull(saved); assertFalse(closed) }
        clickEditorAction("retry")
        awaitSaveState { saved != null && closed }
        compose.runOnIdle { assertEquals(2, attempts); assertEquals("Retry template draft", saved?.capture?.templates?.first()?.name); assertTrue(closed) }
    }

    @Test fun failedWorkflowStateSaveKeepsDraftAndRetriesBeforeClosing() {
        val baseline = WorkspaceConfig.defaults()
        var attempts = 0
        var saved: WorkspaceConfig? = null
        var closed = false
        compose.setContent { MaterialTheme {
            WorkflowStateEditor(baseline, "workflow-workspace", "TODO", false, { closed = true }) { workspaceId, expected, candidate ->
                assertEquals("workflow-workspace", workspaceId)
                assertEquals(baseline, expected)
                attempts++
                if (attempts == 1) error("Workflow save failed")
                saved = candidate
            }
        } }
        compose.onNode(hasSetTextAction() and hasText(label("name"))).performTextReplacement("Next action")
        var phase = "clicking Save"
        try {
            clickEditorAction("save")
            phase = "waiting for the persistence callback"
            awaitSaveState { attempts == 1 }
            phase = "waiting for the failure message"
            compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Workflow save failed").fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            val state = runCatching { compose.runOnIdle { "attempts=$attempts, closed=$closed, saved=$saved" } }.getOrElse { "State unavailable: $it" }
            val tree = runCatching {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                roots.fetchSemanticsNodes().indices.joinToString("\n") { index -> roots[index].printToString() }
            }.getOrElse { "UI tree unavailable: $it" }
            throw AssertionError("Workflow save failed while $phase\n$state\n$tree", failure)
        }
        compose.onNodeWithText("Workflow save failed").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText(label("name"))).assert(hasText("Next action"))
        compose.runOnIdle { assertEquals(1, attempts); assertNull(saved); assertFalse(closed) }
        clickEditorAction("retry")
        awaitSaveState { saved != null && closed }
        compose.runOnIdle { assertEquals(2, attempts); assertEquals("Next action", saved?.workflow?.style("TODO")?.label); assertTrue(closed) }
    }

    @Test fun inFlightTemplateSaveResumesWithOriginalBaselineAfterRestoration() {
        val restoration = StateRestorationTester(compose)
        val baseline = WorkspaceConfig.defaults()
        val completionGate = CompletableDeferred<Unit>()
        val workspace = WorkspaceEntity("original-workspace", "private", "/test", "Test")
        var current by mutableStateOf(baseline)
        var attempts = 0
        var saved: WorkspaceConfig? = null
        var closed = false
        restoration.setContent { MaterialTheme {
            CaptureTemplateEditor(current, WorkspaceSnapshot(workspace = workspace), baseline.capture.templates.first(), { closed = true }, {}) { workspaceId, expected, candidate ->
                assertEquals(workspace.id, workspaceId)
                assertEquals(baseline, expected)
                attempts++
                completionGate.await()
                saved = candidate
            }
        } }
        compose.onNode(hasSetTextAction() and hasText(label("name"))).performTextReplacement("In-flight draft")
        clickEditorAction("save")
        awaitSaveState { attempts == 1 }
        compose.runOnIdle { assertEquals(1, attempts); current = baseline.copy(reminders = ReminderRules(advanceMinutes = 30)) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(label("saving")).performScrollTo().assertIsNotEnabled()
        awaitSaveState { attempts == 2 }
        compose.runOnIdle { assertEquals(2, attempts); assertNull(saved); assertFalse(closed); completionGate.complete(Unit) }
        awaitSaveState { saved != null && closed }
        compose.runOnIdle { assertEquals("In-flight draft", saved?.capture?.templates?.first()?.name); assertTrue(closed) }
    }

    @Test fun pastedConfigurationMustValidateBeforeImport() {
        var imported: WorkspaceConfig? = null
        compose.setContent { MaterialTheme { ConfigurationPasteDialog(close = {}, apply = { imported = it }) } }
        compose.onNode(hasSetTextAction()).performTextInput("{\"version\":2}")
        compose.onNodeWithText(label("import_json")).performScrollTo().performClick()
        compose.runOnIdle { assertNull(imported) }
        compose.onNodeWithText("Use a configuration with version 1").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("{\"version\":1}")
        compose.onNodeWithText(label("import_json")).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(WorkspaceConfig.defaults(), imported) }
    }
}
