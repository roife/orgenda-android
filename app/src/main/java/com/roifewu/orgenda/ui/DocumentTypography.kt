package com.roifewu.orgenda.ui

import android.content.Context
import android.util.TypedValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Document roles share one scale; task emphasis is independent of outline depth.
// Android SP retains system font scaling without changing the rest of Material UI.
internal object DocumentTypography {
    const val Title = 26f
    const val Heading = 20f
    const val Subheading = 18f
    const val Task = 16f
    const val Metadata = 13f
    const val Callout = 16f
    const val Body = 17f
    const val Subheadline = 15f
    const val Footnote = 13f
    const val Caption = 12f
    const val Caption2 = 11f

    val title = style(Title, FontWeight.Bold, lineHeight = 32f)
    val metadata = style(Metadata, lineHeight = 18f)
    val body = style(Body)
    val subheadline = style(Subheadline)
    val footnote = style(Footnote)
    val caption = style(Caption)
    val caption2 = style(Caption2)

    fun headingSize(level: Int, isTask: Boolean = false) =
        if (isTask) Task else when (level) {
            1 -> Heading
            2 -> Subheading
            else -> Callout
        }

    fun headingWeight(isTask: Boolean) =
        if (isTask) FontWeight.Medium else FontWeight.SemiBold

    fun heading(level: Int, isTask: Boolean = false) =
        style(headingSize(level, isTask), headingWeight(isTask))

    private fun style(
        size: Float,
        weight: FontWeight = FontWeight.Normal,
        lineHeight: Float = size * 1.2f,
    ) =
        TextStyle(
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
            fontWeight = weight,
            letterSpacing = 0.sp,
        )

    // RaTeX multiplies by density internally. Convert SP through Android's
    // font scale converter first, including Android 14+ nonlinear scaling.
    fun scaledDp(context: Context, size: Float): Float {
        val metrics = context.resources.displayMetrics
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics) /
            metrics.density
    }
}
