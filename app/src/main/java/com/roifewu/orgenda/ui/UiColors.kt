package com.roifewu.orgenda.ui

import android.content.Context
import android.text.Spannable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.roifewu.orgenda.core.OrgHeading
import com.roifewu.orgenda.core.SearchText
import com.roifewu.orgenda.core.Workflow

private const val DisabledContentAlpha = 0.38f
private const val StateContainerAlpha = 0.10f
private const val MinimumTextContrast = 4.5
private const val ContrastBlendSteps = 20

internal fun orgendaColorScheme(context: Context, dark: Boolean): ColorScheme =
    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

// Standard Material roles stay on MaterialTheme.colorScheme. App-specific colors and opacity
// decisions live here so Compose and native text views use the same palette.
@Immutable
internal class UiColors(private val scheme: ColorScheme) {
    val fieldContainer = scheme.surfaceContainerHigh.copy(alpha = 0.65f)
    val fieldErrorContainer = scheme.errorContainer.copy(alpha = 0.25f)
    val fieldBorder = scheme.outline
    val choiceBorder = scheme.outlineVariant.copy(alpha = 0.6f)
    val disabledBorder = scheme.outlineVariant.copy(alpha = 0.2f)
    val disabledContainer = scheme.onSurface.copy(alpha = 0.04f)
    val disabledContent = scheme.onSurface.copy(alpha = DisabledContentAlpha)
    val disabledMutedContent = scheme.onSurfaceVariant.copy(alpha = DisabledContentAlpha)
    val pickerSelection = scheme.secondaryContainer.copy(alpha = 0.5f)
    val drawerContainer = scheme.primary.copy(alpha = 0.06f)
    val pendingHabit = scheme.onSurfaceVariant.copy(alpha = 0.18f)
    val headingDropContainer = scheme.primary.copy(alpha = StateContainerAlpha)
    val searchHighlight = SearchHighlightColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
    val sourceForeground = scheme.onSurface.toArgb()
    val sourceAccent = scheme.primary.toArgb()
    val sourceSecondary = scheme.secondary.toArgb()
    val sourceCodeAccent = scheme.tertiary.toArgb()
    val sourceMuted = scheme.onSurfaceVariant.toArgb()

    fun workflow(name: String): Color {
        val base = workflowPalette[name] ?: scheme.primary
        val background = scheme.surface.toArgb()
        val black = android.graphics.Color.BLACK
        val white = android.graphics.Color.WHITE
        val target =
            if (ColorUtils.calculateContrast(white, background) >=
                ColorUtils.calculateContrast(black, background)) white else black
        for (step in 0..ContrastBlendSteps) {
            val value = ColorUtils.blendARGB(base.toArgb(), target, step.toFloat() / ContrastBlendSteps)
            if (ColorUtils.calculateContrast(value, background) >= MinimumTextContrast) return Color(value)
        }
        return Color(target)
    }

    fun workflow(configuration: Workflow, token: String): Color {
        val explicit = configuration.style(token).color
        if (explicit != "default") return workflow(explicit)
        return workflow(
            when (token) {
                "NEXT" -> "blue"
                "WAIT" -> "orange"
                "SOMEDAY" -> "purple"
                "URGENT" -> "red"
                "CANCELED" -> "pink"
                else -> "gray"
            }
        )
    }

    fun sourceSyntax(configuration: Workflow): Map<String, Int> = buildMap {
        put("muted", sourceMuted)
        put("secondary", sourceSecondary)
        put("surface", scheme.surfaceContainer.toArgb())
        put("tag", scheme.secondaryContainer.toArgb())
        for (name in listOf("red", "orange", "blue", "green")) put(name, workflow(name).toArgb())
        for (token in configuration.tokens) put("state:$token", workflow(configuration, token).toArgb())
    }
}

private val workflowPalette = mapOf(
    "gray" to Color.Gray,
    "red" to Color(0xffc34b45),
    "orange" to Color(0xffb56417),
    "amber" to Color(0xff977200),
    "green" to Color(0xff348253),
    "cyan" to Color(0xff087f8c),
    "blue" to Color(0xff3975c2),
    "purple" to Color(0xff8851b5),
    "pink" to Color(0xffb8548b),
)

@Immutable
internal data class SearchHighlightColors(val background: Color, val foreground: Color) {
    fun applyTo(text: Spannable, query: String) {
        SearchText.ranges(text.toString(), query).forEach { match ->
            text.setSpan(
                BackgroundColorSpan(background.toArgb()),
                match.first,
                match.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            text.setSpan(
                ForegroundColorSpan(foreground.toArgb()),
                match.first,
                match.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
}

@Composable
internal fun uiColors(): UiColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) { UiColors(scheme) }
}

@Composable fun workflowColor(name: String): Color = uiColors().workflow(name)

@Composable fun workflowColor(item: OrgHeading): Color = workflowColor(item.workflow, item.state)

@Composable
fun workflowColor(workflow: Workflow, token: String): Color = uiColors().workflow(workflow, token)
