package com.roifewu.orgenda.ui

import android.content.Context
import android.util.TypedValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Match the iOS document's default Dynamic Type styles. Android SP retains
// the user's system scaling; these styles do not change the rest of Material UI.
internal object DocumentTypography {
    const val Title = 22f
    const val Heading = 20f
    const val Callout = 16f
    const val Body = 17f
    const val Subheadline = 15f
    const val Footnote = 13f
    const val Caption = 12f
    const val Caption2 = 11f

    val title = style(Title, FontWeight.Bold)
    val body = style(Body)
    val subheadline = style(Subheadline)
    val footnote = style(Footnote)
    val caption = style(Caption)
    val caption2 = style(Caption2)

    fun headingSize(level: Int) =
        when (level) {
            1 -> Heading
            2 -> Callout
            else -> Subheadline
        }

    fun heading(level: Int) =
        style(headingSize(level), if (level == 1) FontWeight.Bold else FontWeight.SemiBold)

    private fun style(size: Float, weight: FontWeight = FontWeight.Normal) =
        TextStyle(
            fontSize = size.sp,
            lineHeight = (size * 1.2f).sp,
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
