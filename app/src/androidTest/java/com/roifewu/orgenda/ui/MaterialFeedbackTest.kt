package com.roifewu.orgenda.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.roifewu.orgenda.LocalPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MaterialFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun fieldExplainsItsErrorAndClearsItWhenCorrectedOrDisabled() {
        var invalid by mutableStateOf(true)
        var enabled by mutableStateOf(true)
        var value by mutableStateOf("bad path")
        compose.setContent {
            OrgendaTheme(LocalPreferences()) {
                Column(Modifier.safeDrawingPadding()) {
                    Field(
                        value,
                        { value = it; invalid = false },
                        "name",
                        Modifier.testTag("field"),
                        invalid = invalid,
                        enabled = enabled,
                        supporting = if (invalid) "Choose an .org file" else "Ready to save",
                    )
                }
            }
        }
        val field = compose.onNodeWithTag("field")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Choose an .org file"))
        field.performTextReplacement("notes.org")
        Espresso.closeSoftKeyboard()
        field.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        compose.onNodeWithText("Ready to save", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { invalid = true; enabled = false }
        field.assertIsNotEnabled().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }

    @Test
    fun changingScopeKeepsTheSelectedFilterVisibleOnANarrowScreen() {
        var scope by mutableStateOf("all")
        var changes = 0
        compose.setContent {
            OrgendaTheme(LocalPreferences()) {
                Column(Modifier.width(280.dp)) {
                    SearchScopeChips(scope) { scope = it; changes++ }
                }
            }
        }
        compose.runOnIdle { scope = "settings" }
        compose.onNodeWithTag("search.scope.settings").assertIsDisplayed().assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose.runOnIdle { scope = "all" }
        compose.onNodeWithTag("search.scope.all").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("search.scope.tasks").performClick().assertIsSelected()
        compose.onNodeWithTag("search.scope.all").assertIsNotSelected()
        compose.runOnIdle { assertEquals(1, changes) }
    }

    @Test fun compactSearchSummaryKeepsBothLabelsReadable() = checkSummary(1f, "light")

    @Test fun largeDarkSearchSummaryKeepsBothLabelsReadable() = checkSummary(2f, "dark")

    private fun checkSummary(fontScale: Float, theme: String) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                OrgendaTheme(LocalPreferences(theme = theme)) {
                    Surface(Modifier.width(320.dp)) {
                        SearchResultsSummary("all", 12345, refreshing = true)
                    }
                }
            }
        }
        compose.onNodeWithTag("search.summary").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        )
        val textNodes = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true)
        assertEquals(2, textNodes.fetchSemanticsNodes().size)
        repeat(2) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            textNodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                it(layouts)
            }
            val layout = layouts.single()
            // FlowRow can retain a wider paragraph measurement; check the actual rendered lines.
            assertTrue(
                "Search summary text is clipped: ${layout.layoutInput.text}",
                !layout.didOverflowHeight &&
                    (0 until layout.lineCount).all { line ->
                        !layout.isLineEllipsized(line) &&
                            layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f
                    },
            )
        }
        val first = textNodes[0].fetchSemanticsNode().boundsInRoot
        val second = textNodes[1].fetchSemanticsNode().boundsInRoot
        assertTrue("Search labels overlap", second.left >= first.right || second.top >= first.bottom)
        compose.onNodeWithTag("search.loading").assertIsDisplayed()
    }
}
