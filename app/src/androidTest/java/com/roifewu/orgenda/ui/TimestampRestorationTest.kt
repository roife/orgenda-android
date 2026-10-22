package com.roifewu.orgenda.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.roifewu.orgenda.EditorState
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.data.FileEntity
import org.junit.Rule
import org.junit.Test

class TimestampRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun previewTimestampEntryAndUnsavedDraftSurviveRestorationAndSwipeDismissal() {
        val models = ViewModelStore()
        val model =
            ViewModelProvider(
                models,
                ViewModelProvider.AndroidViewModelFactory.getInstance(
                    ApplicationProvider.getApplicationContext()
                ),
            )[OrgendaViewModel::class.java]
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = "* TODO 日期草稿\nSCHEDULED: <2026-10-22 Thu>\n"
        val document = model.repository.engine.parse("timestamp.org", text)
        val editor =
            EditorState(FileEntity("ui-only", "ui-only", "timestamp.org"), text, null, document)
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { MaterialTheme { OrgPreview(model, editor, {}, {}) } }
            compose
                .onNodeWithText(readableTimestamp(context, "SCHEDULED: <2026-10-22 Thu>"))
                .performClick()
            compose.onNodeWithTag("recurrence.enabled").performScrollTo().performClick()
            compose
                .onNodeWithTag("recurrence.interval")
                .performScrollTo()
                .performTextReplacement("3")
            compose
                .onNodeWithTag("timestamp.note")
                .performScrollTo()
                .performTextReplacement("尚未保存的日期备注")
            restoration.emulateSavedInstanceStateRestore()
            compose
                .onNodeWithTag("timestamp.note")
                .performScrollTo()
                .assertTextContains("尚未保存的日期备注")
            compose
                .onNodeWithTag("timestamp.source")
                .performScrollTo()
                .assertTextContains("+3d", substring = true)
            compose.onNodeWithTag("timestamp.sheet").performTouchInput {
                swipe(Offset(center.x, 10f), Offset(center.x, height.toFloat() - 10f), 200)
            }
            compose.onNodeWithText(context.label("discard_changes")).assertIsDisplayed()
            compose.onNodeWithText(context.label("continue_editing")).performClick()
            compose
                .onNodeWithTag("timestamp.note")
                .performScrollTo()
                .assertTextContains("尚未保存的日期备注")
            compose
                .onNodeWithTag("timestamp.source")
                .performScrollTo()
                .assertTextContains("+3d", substring = true)
        } finally {
            compose.runOnIdle { models.clear() }
        }
    }
}
