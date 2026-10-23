@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.roifewu.orgenda.ui

import android.content.ClipData
import android.view.View
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.roifewu.orgenda.core.*

private const val orgDragMime = "application/vnd.orgenda.local"

data class OrgDrag(val kind: String, val value: Any)

@Composable
fun Modifier.orgDrag(kind: String, value: Any): Modifier {
    val haptic = LocalHapticFeedback.current
    val latestValue by rememberUpdatedState(value)
    // Checkbox and IconButton consume down before the source sees it. Accept
    // consumed presses too, and reserve bare handles from the row's context menu.
    return dragAndDropSource(
        block = {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val held = awaitLongPressOrCancellation(down.id)
                if (held != null) {
                    held.consume()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    startTransfer(
                        DragAndDropTransferData(
                            ClipData("Orgenda", arrayOf(orgDragMime), ClipData.Item("")),
                            localState = OrgDrag(kind, latestValue),
                        )
                    )
                }
            }
        }
    )
}

// Rich preview titles are Android TextViews: their long press must join the same
// local drag session as Compose rows instead of starting text selection.
internal fun View.startOrgDrag(kind: String, value: Any): Boolean =
    startDragAndDrop(
        ClipData("Orgenda", arrayOf(orgDragMime), ClipData.Item("")),
        View.DragShadowBuilder(this),
        OrgDrag(kind, value),
        0,
    )

@Composable
internal fun Modifier.orgDragScroll(
    list: LazyListState,
    kind: String,
): Modifier {
    val scroll = remember(list) { NativeDragScrollState(list) }
    DragEdgeScroll(list, scroll.pointer != null, scroll.pointer?.y ?: 0f)
    val target = remember(kind, scroll) {
        object : DragAndDropTarget {
            override fun onMoved(event: DragAndDropEvent) { scroll.update(event) }
            override fun onExited(event: DragAndDropEvent) { scroll.pointer = null }
            override fun onEnded(event: DragAndDropEvent) { scroll.pointer = null }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                scroll.pointer = null
                return false
            }
        }
    }
    return onGloballyPositioned { scroll.coordinates = it }.dragAndDropTarget(
        shouldStartDragAndDrop = { (it.toAndroidDragEvent().localState as? OrgDrag)?.kind == kind },
        target = target,
    )
}

@Stable
internal class NativeDragScrollState(private val list: LazyListState) {
    var coordinates: LayoutCoordinates? = null
    var pointer by mutableStateOf<Offset?>(null)

    fun update(event: DragAndDropEvent) {
        val bounds = coordinates?.takeIf { it.isAttached } ?: return
        val native = event.toAndroidDragEvent()
        pointer = bounds.localPositionOf(bounds.findRootCoordinates(), Offset(native.x, native.y)) +
            Offset(0f, list.layoutInfo.viewportStartOffset.toFloat())
    }
}

@Composable
internal fun Modifier.previewHeadingDrop(
    drag: OutlineDragState,
    list: LazyListState,
    headings: List<OrgHeading>,
    session: Long,
    move: (OrgHeading, OrgHeading, HeadingPlacement) -> Unit,
): Modifier {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentHeadings by rememberUpdatedState(headings)
    val currentMove by rememberUpdatedState(move)
    LaunchedEffect(session, headings.firstOrNull()?.sourceHash) { drag.clear() }
    DragEdgeScroll(list, drag.source != null && drag.moved, drag.pointer.y)
    fun update(event: DragAndDropEvent): Boolean {
        val payload = event.toAndroidDragEvent().localState as? OrgDrag ?: return false
        val source = payload.value as? HeadingDrag ?: return false
        val first = currentHeadings.firstOrNull() ?: return false
        if (payload.kind != "heading" || source.session != session ||
            source.item.path != first.path || source.item.sourceHash != first.sourceHash
        ) return false
        val bounds = coordinates?.takeIf { it.isAttached } ?: return false
        val native = event.toAndroidDragEvent()
        drag.pointer = bounds.localPositionOf(bounds.findRootCoordinates(), Offset(native.x, native.y)) +
            Offset(0f, list.layoutInfo.viewportStartOffset.toFloat())
        drag.source = source
        drag.moved = true
        return true
    }
    val target = remember(session, list) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { update(event) }
            override fun onMoved(event: DragAndDropEvent) { update(event) }
            override fun onExited(event: DragAndDropEvent) { drag.clear() }
            override fun onEnded(event: DragAndDropEvent) { drag.clear() }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val valid = update(event)
                val source = drag.source
                val drop = outlineDropAt(drag, list, currentHeadings, session) { "heading:${it.start}" }
                drag.clear()
                if (!valid || source == null || drop == null) return false
                currentMove(source.item, drop.target, drop.placement)
                return true
            }
        }
    }
    return onGloballyPositioned { coordinates = it }.dragAndDropTarget(
        shouldStartDragAndDrop = { (it.toAndroidDragEvent().localState as? OrgDrag)?.kind == "heading" },
        target = target,
    )
}

@Composable
internal fun Modifier.headingDropIndicator(target: OrgHeading, placement: HeadingPlacement?): Modifier {
    val color = MaterialTheme.colorScheme.primary
    val container = uiColors().headingDropContainer
    val context = LocalContext.current
    return testTag("heading.drop.${target.start}").semantics {
        placement?.let { stateDescription = context.label("placement_${it.name.lowercase()}") }
    }.drawBehind {
        placement?.let {
            if (it == HeadingPlacement.CHILD) drawRect(container)
            val inset = if (it == HeadingPlacement.CHILD) UiSpacing.edge.toPx() else 0f
            val line = UiSizes.activeBorder.toPx()
            val y = if (it == HeadingPlacement.BEFORE) line / 2 else size.height - line / 2
            drawLine(color, Offset(inset, y), Offset(size.width, y), line)
        }
    }
}

@Composable
fun Modifier.orgDrop(
    kind: String,
    targeted: (Boolean) -> Unit = {},
    accepts: (Any) -> Boolean = { true },
    drop: (Any) -> Unit,
): Modifier {
    val latest by rememberUpdatedState(drop)
    val latestTarget by rememberUpdatedState(targeted)
    val latestAccepts by rememberUpdatedState(accepts)
    val callback =
        remember(kind) {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    latestTarget(true)
                }

                override fun onExited(event: DragAndDropEvent) {
                    latestTarget(false)
                }

                override fun onEnded(event: DragAndDropEvent) {
                    latestTarget(false)
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val payload = event.toAndroidDragEvent().localState as? OrgDrag ?: return false
                    if (payload.kind != kind || !latestAccepts(payload.value)) {
                        latestTarget(false)
                        return false
                    }
                    latestTarget(false)
                    latest(payload.value)
                    return true
                }
            }
        }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            val payload = event.toAndroidDragEvent().localState as? OrgDrag
            event.mimeTypes().contains(orgDragMime) &&
                payload?.kind == kind &&
                latestAccepts(payload.value)
        },
        target = callback,
    )
}

internal data class HeadingDrag(val item: OrgHeading, val session: Long)

internal fun headingDropPlacement(y: Float, height: Float): HeadingPlacement =
    when {
        y < height * 0.25f -> HeadingPlacement.BEFORE
        y > height * 0.75f -> HeadingPlacement.AFTER
        else -> HeadingPlacement.CHILD
    }

internal fun acceptsHeadingDrop(drag: HeadingDrag, target: OrgHeading, session: Long): Boolean =
    drag.session == session &&
        drag.item.path == target.path &&
        drag.item.sourceHash == target.sourceHash &&
        target.start !in drag.item.start until drag.item.subtreeEnd

@Composable
internal fun Modifier.orgHeadingDrop(
    target: OrgHeading,
    session: Long,
    move: (OrgHeading, HeadingPlacement) -> Unit,
): Modifier {
    var placement by remember(target.start, session) { mutableStateOf<HeadingPlacement?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestTarget by rememberUpdatedState(target)
    val latestMove by rememberUpdatedState(move)
    val color = MaterialTheme.colorScheme.primary
    val dropContainer = uiColors().headingDropContainer
    val context = LocalContext.current
    LaunchedEffect(target.sourceHash) { placement = null }
    fun payload(event: DragAndDropEvent): HeadingDrag? {
        val data = event.toAndroidDragEvent().localState as? OrgDrag ?: return null
        return (data.value as? HeadingDrag)?.takeIf {
            data.kind == "heading" && acceptsHeadingDrop(it, latestTarget, session)
        }
    }
    fun position(event: DragAndDropEvent): HeadingPlacement? {
        val bounds = coordinates?.takeIf { it.isAttached } ?: return null
        val native = event.toAndroidDragEvent()
        val point = bounds.localPositionOf(bounds.findRootCoordinates(), Offset(native.x, native.y))
        return headingDropPlacement(point.y, bounds.size.height.toFloat())
    }
    val callback =
        remember(session) {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    placement = if (payload(event) != null) position(event) else null
                }

                override fun onMoved(event: DragAndDropEvent) {
                    placement = if (payload(event) != null) position(event) else null
                }

                override fun onExited(event: DragAndDropEvent) {
                    placement = null
                }

                override fun onEnded(event: DragAndDropEvent) {
                    placement = null
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val source = payload(event)
                    val destination = position(event)
                    placement = null
                    if (source == null || destination == null) return false
                    latestMove(source.item, destination)
                    return true
                }
            }
        }
    return onGloballyPositioned { coordinates = it }
        .testTag("heading.drop.${target.start}")
        .semantics {
            placement?.let { stateDescription = context.label("placement_${it.name.lowercase()}") }
        }
        .drawBehind {
            placement?.let { drop ->
                if (drop == HeadingPlacement.CHILD) drawRect(dropContainer)
                val inset = if (drop == HeadingPlacement.CHILD) UiSpacing.edge.toPx() else 0f
                val line = UiSizes.activeBorder.toPx()
                val y = if (drop == HeadingPlacement.BEFORE) line / 2 else size.height - line / 2
                drawLine(color, Offset(inset, y), Offset(size.width, y), line)
            }
        }
        .dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                event.mimeTypes().contains(orgDragMime) && payload(event) != null
            },
            target = callback,
        )
}
