package com.roifewu.orgenda.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.core.NativeOrgEngine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MaterialLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactTaskKeepsItsActionsAndTitleReadable() = checkTaskLayout(1f, false)

    @Test fun largeTextDarkTaskKeepsItsActionsAndTitleReadable() = checkTaskLayout(2f, true)

    @Test
    fun sectionHeadingAnnouncesTitleAndCountTogether() {
        compose.setContent {
            MaterialTheme {
                ListSectionHeader("Tasks", 3)
            }
        }
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onAllNodes(heading).assertCountEquals(1)
        compose.onNode(heading).assertTextEquals("Tasks", "3")
        compose.onAllNodes(hasText("3") and !hasText("Tasks")).assertCountEquals(0)
    }

    private fun checkTaskLayout(fontScale: Float, dark: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val item =
            NativeOrgEngine().use {
                it.parse("inbox.org", "* TODO [#A] 整理项目计划 Review project plan :work:home:\n")
                    .headings
                    .single()
            }
        var completions = 0
        var pixelsPerDp = 1f
        compose.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
            pixelsPerDp = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(pixelsPerDp, fontScale),
                LocalConfiguration provides configuration,
                LocalContext provides context.createConfigurationContext(configuration),
            ) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface {
                        Column(Modifier.width(320.dp)) {
                            ListSectionHeader("Tasks", 1)
                            TaskRow(item, {}, { completions++ })
                        }
                    }
                }
            }
        }
        val title = compose.onNodeWithTag("agenda.item.title.${item.id}", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(
            "Task title is clipped at font scale $fontScale",
            layouts.single().hasVisualOverflow,
        )
        val titleBounds = title.fetchSemanticsNode().boundsInRoot
        val complete =
            compose.onNodeWithTag("agenda.item.complete.${item.id}", useUnmergedTree = true)
        val completeBounds = complete.fetchSemanticsNode().boundsInRoot
        val more =
            compose.onNodeWithContentDescription(
                context.label("more_actions"),
                useUnmergedTree = true,
            )
        assertTrue(
            "Completion target is smaller than 48 dp",
            completeBounds.width >= 48 * pixelsPerDp - 1 &&
                completeBounds.height >= 48 * pixelsPerDp - 1,
        )
        assertTrue("Title overlaps completion control", titleBounds.left >= completeBounds.right)
        assertTrue(
            "Title overlaps overflow control",
            titleBounds.right <= more.fetchSemanticsNode().boundsInRoot.left,
        )
        compose.onNodeWithText("inbox", useUnmergedTree = true).assertIsDisplayed()
        complete.performClick()
        compose.runOnIdle { assertEquals(1, completions) }
    }
}
