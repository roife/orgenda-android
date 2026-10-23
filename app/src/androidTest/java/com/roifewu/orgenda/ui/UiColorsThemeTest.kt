package com.roifewu.orgenda.ui

import android.content.res.Configuration
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.LocalPreferences
import com.roifewu.orgenda.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UiColorsThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun searchAndWorkflowColorsUpdateWhenTheAppThemeChanges() {
        var dark by mutableStateOf(false)
        lateinit var scheme: ColorScheme
        lateinit var colors: UiColors
        compose.setContent {
            OrgendaTheme(LocalPreferences(theme = if (dark) "dark" else "light")) {
                val currentScheme = MaterialTheme.colorScheme
                val currentColors = uiColors()
                SideEffect {
                    scheme = currentScheme
                    colors = currentColors
                }
                HighlightText("Find this match", "match")
            }
        }
        fun assertHighlight() {
            val text = compose.onNodeWithText("Find this match")
                .fetchSemanticsNode().config[SemanticsProperties.Text].single()
            val highlight = text.spanStyles.single()
            assertEquals(10, highlight.start)
            assertEquals(15, highlight.end)
            assertEquals(scheme.tertiaryContainer, highlight.item.background)
            assertEquals(scheme.onTertiaryContainer, highlight.item.color)
        }
        assertHighlight()
        val lightColors = colors
        compose.runOnIdle { dark = true }
        assertHighlight()
        assertNotSame(lightColors, colors)
        assertNotEquals(lightColors.workflow("green"), colors.workflow("green"))
    }

    @Test
    fun standaloneNativeEditorUsesTheContextPaletteInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (dark in listOf(false, true)) {
                val context = instrumentation.targetContext
                val configuration = Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val themedContext = ContextThemeWrapper(
                    context.createConfigurationContext(configuration),
                    R.style.Theme_Orgenda,
                )
                val scheme = if (dark) dynamicDarkColorScheme(themedContext) else dynamicLightColorScheme(themedContext)
                val editor = NativeSourceEditor(themedContext, EditorHistory())
                try {
                    assertEquals(scheme.onSurface.toArgb(), editor.currentTextColor)
                    assertEquals(scheme.primary.toArgb(), editor.accent)
                    assertEquals(scheme.tertiary.toArgb(), editor.codeAccent)
                    assertEquals(scheme.surfaceContainer.toArgb(), editor.syntaxColors["surface"])
                } finally {
                    editor.dispose()
                }
            }
        }
    }
}
