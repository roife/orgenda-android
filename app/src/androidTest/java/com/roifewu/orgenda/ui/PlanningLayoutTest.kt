package com.roifewu.orgenda.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.EditorState
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.data.FileEntity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlanningLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scheduledAndDeadlineControlsHaveSeparateBounds() = checkPlanningLayout(1f)

    @Test fun wrappedPlanningControlsKeepTheirSpacingWithLargeText() = checkPlanningLayout(2f)

    private fun checkPlanningLayout(fontScale: Float) {
        val models = ViewModelStore()
        val model =
            ViewModelProvider(
                models,
                ViewModelProvider.AndroidViewModelFactory.getInstance(
                    ApplicationProvider.getApplicationContext()
                ),
            )[OrgendaViewModel::class.java]
        val scheduled = "SCHEDULED: <2026-10-23 Fri 09:00-10:00 +1d -2d>"
        val deadline = "DEADLINE: <2026-10-24 Sat>"
        val source = "* TODO Planning\n$scheduled $deadline\n"
        val document = model.repository.engine.parse("planning-layout.org", source)
        val state =
            EditorState(FileEntity("ui-only", "ui-only", document.path), source, null, document)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration =
            Configuration(context.resources.configuration).apply {
                this.fontScale = fontScale
                setLocales(LocaleList.forLanguageTags("zh-TW"))
            }
        val scaledContext = context.createConfigurationContext(configuration)
        var minimumGap = 0f
        try {
            compose.setContent {
                val density = LocalDensity.current.density
                minimumGap = with(Density(density, fontScale)) { 6.dp.toPx() }
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalContext provides scaledContext,
                        LocalConfiguration provides configuration,
                        LocalDensity provides Density(density, fontScale),
                    ) {
                        Box(Modifier.width(320.dp)) { OrgPreview(model, state, {}, {}) }
                    }
                }
            }
            val scheduledControl =
                compose.onNode(
                    hasText(readableTimestamp(scaledContext, scheduled, compact = true)) and
                        hasClickAction()
                )
            val deadlineControl =
                compose.onNode(
                    hasText(readableTimestamp(scaledContext, deadline, compact = true)) and
                        hasClickAction()
                )
            scheduledControl.assertIsDisplayed()
            deadlineControl.assertIsDisplayed()
            val scheduledBounds = scheduledControl.fetchSemanticsNode().boundsInRoot
            val deadlineBounds = deadlineControl.fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Planning controls have no gap: scheduled=$scheduledBounds, deadline=$deadlineBounds",
                deadlineBounds.top - scheduledBounds.bottom >= minimumGap - 1f,
            )
        } finally {
            compose.runOnIdle { models.clear() }
        }
    }
}
