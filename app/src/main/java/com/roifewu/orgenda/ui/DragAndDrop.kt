@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.roifewu.orgenda.ui

import android.content.ClipData
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
import androidx.compose.ui.unit.dp
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
                if (drop == HeadingPlacement.CHILD) drawRect(color.copy(alpha = 0.10f))
                val inset = if (drop == HeadingPlacement.CHILD) 16.dp.toPx() else 0f
                val line = 2.dp.toPx()
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
