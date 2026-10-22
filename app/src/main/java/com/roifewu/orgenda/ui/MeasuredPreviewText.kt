package com.roifewu.orgenda.ui

import android.content.Context
import android.view.View.MeasureSpec
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.viewinterop.AndroidView

private class PreviewTextState(context: Context) {
    val view =
        TextView(context).apply {
            setTextIsSelectable(true)
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
        }
    var text: CharSequence? = null
}

@Composable
internal fun MeasuredPreviewText(
    text: CharSequence,
    modifier: Modifier = Modifier,
    update: (TextView) -> Unit,
) {
    val context = LocalContext.current
    val state = remember(context) { PreviewTextState(context) }
    Layout(
        modifier = modifier,
        content = { key(state) { AndroidView(factory = { state.view }) } },
    ) { children, constraints ->
        val view = state.view
        update(view)
        if (state.text !== text) {
            view.text = text
            state.text = text
        }
        // AndroidView applies its previous fixed constraints as View minimums.
        // Clear them so shorter rendered text can shrink again.
        view.minimumWidth = 0
        view.minimumHeight = 0
        // Text/spans can finish rendering after the parent has been measured.
        // Measure their full height in this same Compose pass, before measuring
        // the AndroidView with the resulting dimensions.
        val widthSpec =
            when {
                constraints.hasFixedWidth ->
                    MeasureSpec.makeMeasureSpec(constraints.maxWidth, MeasureSpec.EXACTLY)
                constraints.hasBoundedWidth ->
                    MeasureSpec.makeMeasureSpec(constraints.maxWidth, MeasureSpec.AT_MOST)
                else -> MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            }
        view.forceLayout()
        view.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val width = constraints.constrainWidth(view.measuredWidth)
        val height = constraints.constrainHeight(view.measuredHeight)
        val child = children.single().measure(Constraints.fixed(width, height))
        layout(width, height) { child.placeRelative(0, 0) }
    }
}
