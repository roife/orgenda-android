package com.roifewu.orgenda.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.core.HeadingPlacement
import com.roifewu.orgenda.core.OrgHeading
import kotlinx.coroutines.delay

internal data class OutlineDrop(val target: OrgHeading, val placement: HeadingPlacement)

@Stable
internal class OutlineDragState {
    var source by mutableStateOf<HeadingDrag?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var rowHeight by mutableIntStateOf(0)
    var moved by mutableStateOf(false)

    fun clear() {
        source = null
        moved = false
    }
}

// All hit testing uses list-local coordinates, including indentation and the space after the
// last row. The sheet's window and Android's platform drag-event coordinates are not involved.
internal fun outlineDropAt(
    drag: OutlineDragState,
    list: LazyListState,
    headings: List<OrgHeading>,
    session: Long,
    key: (OrgHeading) -> Any = { it.start },
): OutlineDrop? {
    val source = drag.source ?: return null
    val layout = list.layoutInfo
    val point = drag.pointer
    if (point.x < 0 || point.x > layout.viewportSize.width ||
        point.y < layout.viewportStartOffset || point.y > layout.viewportEndOffset
    ) return null
    val byKey = headings.associateBy(key)
    val row = layout.visibleItemsInfo.filter { it.key in byKey }.minByOrNull {
        when {
            point.y < it.offset -> it.offset - point.y
            point.y > it.offset + it.size -> point.y - it.offset - it.size
            else -> 0f
        }
    } ?: return null
    val target = byKey[row.key] ?: return null
    if (!acceptsHeadingDrop(source, target, session)) return null
    return OutlineDrop(target, headingDropPlacement(point.y - row.offset, row.size.toFloat()))
}

@Composable
internal fun DragEdgeScroll(list: LazyListState, active: Boolean, pointerY: Float) {
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    val currentY by rememberUpdatedState(pointerY)
    LaunchedEffect(list, active) {
        while (active) {
            delay(16)
            val layout = list.layoutInfo
            val top = layout.viewportStartOffset.toFloat()
            val bottom = layout.viewportEndOffset.toFloat()
            val y = currentY
            val speed = when {
                y < top || y > bottom -> 0f
                y < top + edge -> -(top + edge - y) / edge
                y > bottom - edge -> (y - bottom + edge) / edge
                else -> 0f
            }
            if ((speed < 0 && list.canScrollBackward) || (speed > 0 && list.canScrollForward))
                list.scrollBy(speed * edge * 12f * 0.016f)
        }
    }
}

@Composable
internal fun Modifier.outlineDrag(
    drag: OutlineDragState,
    list: LazyListState,
    headings: List<OrgHeading>,
    session: Long,
    revision: String,
    move: (OrgHeading, OrgHeading, HeadingPlacement) -> Unit,
): Modifier {
    val currentHeadings by rememberUpdatedState(headings)
    val currentMove by rememberUpdatedState(move)
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(revision, session) { drag.clear() }
    DragEdgeScroll(list, drag.source != null && drag.moved, drag.pointer.y)
    return pointerInput(session, revision) {
        detectDragGesturesAfterLongPress(
            onDragStart = { point ->
                val row = list.layoutInfo.visibleItemsInfo.firstOrNull {
                    point.y >= it.offset && point.y < it.offset + it.size
                } ?: return@detectDragGesturesAfterLongPress
                val heading = currentHeadings.firstOrNull { it.start == row.key }
                    ?: return@detectDragGesturesAfterLongPress
                drag.pointer = point
                drag.rowHeight = row.size
                drag.moved = false
                drag.source = HeadingDrag(heading, session)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDrag = { change, amount ->
                if (drag.source != null) {
                    change.consume()
                    drag.pointer += amount
                    drag.moved = true
                }
            },
            onDragEnd = {
                val source = drag.source
                val destination = outlineDropAt(drag, list, currentHeadings, session)
                drag.clear()
                if (source != null && destination != null) {
                    currentMove(source.item, destination.target, destination.placement)
                }
            },
            onDragCancel = { drag.clear() },
        )
    }
}
