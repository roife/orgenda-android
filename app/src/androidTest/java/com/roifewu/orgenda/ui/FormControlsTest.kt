@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roifewu.orgenda.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.LocalPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FormControlsTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun searchableChoiceCommitsOnlyTheTappedOptionAndReleasesTheModal() {
        var value by mutableStateOf("notes/0.org")
        var changes = 0
        val modalCount = mutableIntStateOf(0)
        val choices = (0..11).map { "notes/$it.org" }
        compose.setContent {
            OrgendaTheme(LocalPreferences()) {
                CompositionLocalProvider(LocalModalCount provides modalCount) {
                    Choice(
                        value,
                        choices,
                        "template",
                        {
                            value = it
                            changes++
                        },
                        labels = { "Journal $it" },
                    )
                }
            }
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("choice.template").performClick()
        compose.onNodeWithTag("choice.option.notes/0.org").assertIsSelected()
        compose.onNodeWithTag("choice.search").performTextInput("11.org")
        compose.onNodeWithTag("choice.option.notes/0.org").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, changes)
            assertEquals(1, modalCount.intValue)
        }
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("choice.option.notes/11.org").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("notes/11.org", value)
            assertEquals(1, changes)
            assertEquals(0, modalCount.intValue)
        }
        compose.onNodeWithTag("choice.search").assertDoesNotExist()
    }

    @Test
    fun closingAChoiceLeavesTheValueAndDraftUnchanged() {
        var value = "first"
        var draft by mutableStateOf("unsaved notes")
        var parentClosed = false
        compose.setContent {
            OrgendaTheme(LocalPreferences()) {
                ModalBottomSheet(onDismissRequest = { parentClosed = true }) {
                    Column {
                        Field(draft, { draft = it }, "notes", Modifier.testTag("draft"))
                        Choice(value, listOf("first", "second"), "template", { value = it })
                    }
                }
            }
        }
        compose.onNodeWithTag("choice.template").performClick()
        compose.onNodeWithContentDescription(context.label("close")).performClick()
        compose.onNodeWithTag("choice.option.first").assertDoesNotExist()
        compose.onNodeWithTag("draft").assertTextContains("unsaved notes")
        compose.runOnIdle {
            assertEquals("first", value)
            assertEquals("unsaved notes", draft)
            assertFalse(parentClosed)
        }
    }

    @Test
    fun openChoiceAndSearchSurviveStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var observed = ""
        restoration.setContent {
            var value by rememberSaveable { mutableStateOf("file-0") }
            observed = value
            OrgendaTheme(LocalPreferences()) {
                Choice(value, (0..11).map { "file-$it" }, "template", { value = it })
            }
        }
        compose.onNodeWithTag("choice.template").performClick()
        compose.onNodeWithTag("choice.search").performTextInput("file-11")
        Espresso.closeSoftKeyboard()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("choice.search").assertTextContains("file-11")
        compose.onNodeWithTag("choice.option.file-11").performClick()
        compose.runOnIdle { assertEquals("file-11", observed) }
    }

    @Test
    fun disabledChoicesCannotOpenOrChangeSelection() {
        var changes = 0
        compose.setContent {
            OrgendaTheme(LocalPreferences()) {
                Column {
                    Choice(
                        "first",
                        listOf("first", "second"),
                        "template",
                        { changes++ },
                        enabled = false,
                    )
                    Choice(
                        "A",
                        listOf("A", "B"),
                        "priority",
                        { changes++ },
                        enabled = false,
                        presentation = ChoicePresentation.Segmented,
                    )
                    Choice(
                        "task",
                        listOf("task", "event"),
                        "kind",
                        { changes++ },
                        enabled = false,
                        presentation = ChoicePresentation.Chips,
                    )
                }
            }
        }
        listOf("choice.template", "choice.priority.B", "choice.kind.event").forEach { tag ->
            compose.onNodeWithTag(tag).assertIsNotEnabled().performTouchInput { click() }
        }
        compose.onNodeWithTag("choice.option.first").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, changes) }
    }

    @Test
    fun inlineChoicesAndTextInputKeepTheirStateAcrossThemeChanges() {
        var dark by mutableStateOf(false)
        var title by mutableStateOf("Plan a quieter week")
        var priority by mutableStateOf("B")
        var kind by mutableStateOf("task")
        compose.setContent {
            OrgendaTheme(LocalPreferences(theme = if (dark) "dark" else "light")) {
                Surface {
                    Column(
                        Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Field(title, { title = it }, "title", Modifier.testTag("title"))
                        Choice(
                            priority,
                            listOf("—", "A", "B", "C"),
                            "priority",
                            { priority = it },
                            presentation = ChoicePresentation.Segmented,
                        )
                        Choice(
                            kind,
                            listOf("task", "event", "project", "habit", "note"),
                            "kind",
                            { kind = it },
                            presentation = ChoicePresentation.Chips,
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("title").performTextReplacement("My weekly plan")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("choice.priority.A").performClick().assertIsSelected()
        compose.onNodeWithTag("choice.priority.B").assertIsNotSelected()
        compose.onNodeWithTag("choice.kind.event").performClick().assertIsSelected()
        compose.onNodeWithTag("choice.kind.task").assertIsNotSelected()
        compose.runOnIdle { dark = true }
        compose.onNodeWithTag("title").assertTextContains("My weekly plan")
        compose.onNodeWithTag("choice.priority.A").assertIsSelected()
        compose.onNodeWithTag("choice.kind.event").assertIsSelected()
        compose.runOnIdle {
            assertEquals("A", priority)
            assertEquals("event", kind)
        }
    }

    @Test
    fun largeTextUsesWrappingChoicesWithoutClippingLabels() {
        val options = listOf("跟随系统设置", "始终使用浅色主题", "始终使用深色主题")
        var value by mutableStateOf(options.first())
        compose.setContent {
            val density = LocalDensity.current.density
            OrgendaTheme(LocalPreferences(theme = "light")) {
                CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                    Surface {
                        Column(
                            Modifier.width(280.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Choice(
                                value,
                                options,
                                "appearance",
                                { value = it },
                                presentation = ChoicePresentation.Segmented,
                                labels = { it },
                            )
                        }
                    }
                }
            }
        }
        val bounds = options.map { option ->
            compose
                .onNodeWithTag("choice.appearance.$option")
                .performScrollTo()
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        }
        assertTrue(bounds.zipWithNext().all { (previous, next) -> next.top >= previous.bottom })
        options.forEach { option ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(option, useUnmergedTree = true).performSemanticsAction(
                SemanticsActions.GetTextLayoutResult
            ) {
                it(layouts)
            }
            assertTrue(layouts.isNotEmpty())
            // The paragraph can include unused space; check actual line widths for clipping.
            assertTrue(
                "Clipped label '$option': ${layouts.map { "size=${it.size}, lineWidths=${(0 until it.lineCount).map { line -> it.getLineRight(line) - it.getLineLeft(line) }}, heightOverflow=${it.didOverflowHeight}" }}",
                layouts.all { layout ->
                    !layout.didOverflowHeight &&
                        (0 until layout.lineCount).all { line ->
                            !layout.isLineEllipsized(line) &&
                                layout.getLineRight(line) - layout.getLineLeft(line) <=
                                    layout.size.width + 1f
                        }
                },
            )
        }
        compose
            .onNodeWithTag("choice.appearance.${options.last()}")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose.runOnIdle { assertEquals(options.last(), value) }
    }
}
