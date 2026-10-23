package com.roifewu.orgenda.ui

import android.app.Application
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.roifewu.orgenda.core.KeywordStyle
import com.roifewu.orgenda.core.Workflow
import com.roifewu.orgenda.core.WorkspaceConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UiColorsTest {
    @Test
    fun workflowTextStaysReadableOnLightDarkAndMidtoneSurfaces() {
        val surfaces = listOf(Color.White, Color.Black, Color(0xff777777), Color(0xffaaaaaa))
        for (surface in surfaces) {
            val colors = UiColors(lightColorScheme(surface = surface))
            for (name in WorkspaceConfig.colors + "unknown") {
                val contrast = ColorUtils.calculateContrast(colors.workflow(name).toArgb(), surface.toArgb())
                assertTrue("$name on $surface has contrast $contrast", contrast >= 4.5)
            }
        }
    }

    @Test
    fun nativeSyntaxUsesTheSameWorkflowColorsAndHonorsOverrides() {
        val workflow = Workflow(keywords = mapOf("TODO" to KeywordStyle(color = "purple")))
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            val colors = UiColors(scheme)
            val syntax = colors.sourceSyntax(workflow)
            assertEquals(colors.workflow("purple").toArgb(), syntax["state:TODO"])
            assertEquals(colors.workflow(workflow, "DONE").toArgb(), syntax["state:DONE"])
            assertEquals(colors.workflow("blue"), colors.workflow(workflow, "NEXT"))
            assertEquals(scheme.surfaceContainer.toArgb(), syntax["surface"])
            assertEquals(scheme.onSurfaceVariant.toArgb(), syntax["muted"])
        }
    }

    @Test
    fun nativeSearchHighlightsUseThemeForegroundAndPreserveOtherSyntax() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            val text = SpannableString("keep CAFÉ cafe")
            val syntax = ForegroundColorSpan(scheme.primary.toArgb())
            text.setSpan(syntax, 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            UiColors(scheme).searchHighlight.applyTo(text, "cafe")
            assertEquals("keep CAFÉ cafe", text.toString())
            val backgrounds = text.getSpans(0, text.length, BackgroundColorSpan::class.java)
            assertEquals(2, backgrounds.size)
            assertEquals(listOf(5, 10), backgrounds.map { text.getSpanStart(it) })
            assertEquals(listOf(9, 14), backgrounds.map { text.getSpanEnd(it) })
            backgrounds.forEach { assertEquals(scheme.tertiaryContainer.toArgb(), it.backgroundColor) }
            val foregrounds = text.getSpans(5, text.length, ForegroundColorSpan::class.java)
            assertEquals(2, foregrounds.size)
            foregrounds.forEach { assertEquals(scheme.onTertiaryContainer.toArgb(), it.foregroundColor) }
            assertEquals(0, text.getSpanStart(syntax))
            assertEquals(4, text.getSpanEnd(syntax))
            assertTrue(
                ColorUtils.calculateContrast(scheme.onTertiaryContainer.toArgb(), scheme.tertiaryContainer.toArgb()) >= 4.5
            )
        }
    }

    @Test
    fun blankSearchLeavesNativeTextUntouched() {
        val text = SpannableString("TODO keep syntax")
        UiColors(lightColorScheme()).searchHighlight.applyTo(text, " ")
        assertTrue(text.getSpans(0, text.length, Any::class.java).isEmpty())
    }
}
