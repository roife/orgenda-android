package com.roifewu.orgenda.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

internal object UiSpacing {
    // Shared content spacing scale. Keep component geometry separate in UiSizes.
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    val edge = lg
    val listItemGap = lg
    val outlineIndent = md
    val supportingTextExtraVertical = xs
    // Compact controls keep their established intermediate spacing.
    val controlGap = 6.dp
    val sectionInset = 20.dp
    val choiceItemVertical = 14.dp
    val priorityLabelHorizontal = 5.dp
    val taskLeadingInset = xs
    val taskStatusGap = xs
    val taskTextInset = taskLeadingInset + UiSizes.touchTarget + taskStatusGap
    // Keep FAB clearance shared across lists and across both document modes.
    val listBottomWithFab = UiSizes.fab + edge + xl
    val documentBottomWithFab = UiSizes.fab + edge * 2
}

// Material ListItem supplies its own edge padding inside an already padded form.
internal fun Modifier.formListItem(): Modifier = layout { measurable, constraints ->
    val inset = UiSpacing.edge.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = inset * 2))
    layout((placeable.width - inset * 2).coerceAtLeast(0), placeable.height) {
        placeable.placeRelative(-inset, 0)
    }
}
