@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.roifewu.orgenda.ui

import android.content.ClipData
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

private const val orgDragMime = "application/vnd.orgenda.local"
data class OrgDrag(val kind: String, val value: Any)

@Composable fun Modifier.orgDrag(kind: String, value: Any): Modifier {
    val haptic = LocalHapticFeedback.current
    return dragAndDropSource { _ ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        DragAndDropTransferData(ClipData("Orgenda", arrayOf(orgDragMime), ClipData.Item("")), localState = OrgDrag(kind, value))
    }
}

@Composable fun Modifier.orgDrop(kind: String, targeted: (Boolean) -> Unit = {}, drop: (Any) -> Unit): Modifier {
    val latest by rememberUpdatedState(drop)
    val latestTarget by rememberUpdatedState(targeted)
    val callback = remember(kind) { object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) { latestTarget(true) }
        override fun onExited(event: DragAndDropEvent) { latestTarget(false) }
        override fun onEnded(event: DragAndDropEvent) { latestTarget(false) }
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val payload = event.toAndroidDragEvent().localState as? OrgDrag ?: return false
            if (payload.kind != kind) return false
            latestTarget(false); latest(payload.value); return true
        }
    } }
    return dragAndDropTarget(shouldStartDragAndDrop = { event -> event.mimeTypes().contains(orgDragMime) && (event.toAndroidDragEvent().localState as? OrgDrag)?.kind == kind }, target = callback)
}
