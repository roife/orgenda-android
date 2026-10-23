package com.roifewu.orgenda.ui

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.layout.onGloballyPositioned
import com.roifewu.orgenda.data.FileEntity
import com.roifewu.orgenda.data.WorkspaceSnapshot

// Resolve the row at release time. A folder composed only after auto-scrolling
// was not present for Android's drag-start event, so it cannot own a native target.
@Composable
internal fun Modifier.fileListDrop(
    list: LazyListState,
    snapshot: WorkspaceSnapshot,
    targeted: (String?) -> Unit,
    move: (FileEntity, String) -> Unit,
): Modifier {
    val scroll = remember(list) { NativeDragScrollState(list) }
    var source by remember(list) { mutableStateOf<FileEntity?>(null) }
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentMove by rememberUpdatedState(move)
    val currentTargeted by rememberUpdatedState(targeted)
    fun destination(): FileEntity? {
        val from = source ?: return null
        val point = scroll.pointer ?: return null
        val layout = list.layoutInfo
        if (point.x !in 0f..layout.viewportSize.width.toFloat() ||
            point.y < layout.viewportStartOffset || point.y > layout.viewportEndOffset
        ) return null
        val key = layout.visibleItemsInfo.firstOrNull {
            point.y >= it.offset && point.y < it.offset + it.size
        }?.key as? String ?: return null
        val id = key.removePrefix("pin:").removePrefix("recent:")
        return currentSnapshot.files.firstOrNull {
            it.id == id && it.directory && !it.trashed && acceptsFileDrop(currentSnapshot, from, it.path)
        }
    }
    val destination by remember(list) { derivedStateOf { destination()?.path } }
    LaunchedEffect(destination) { currentTargeted(destination) }
    DragEdgeScroll(list, source != null && scroll.pointer != null, scroll.pointer?.y ?: 0f)
    fun clear() {
        source = null
        scroll.pointer = null
        currentTargeted(null)
    }
    fun update(event: DragAndDropEvent) {
        val data = event.toAndroidDragEvent().localState as? OrgDrag
        source = data?.takeIf { it.kind == "file" }?.value as? FileEntity
        scroll.update(event)
    }
    val target = remember(list) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { update(event) }
            override fun onMoved(event: DragAndDropEvent) { update(event) }
            override fun onExited(event: DragAndDropEvent) { clear() }
            override fun onEnded(event: DragAndDropEvent) { clear() }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                update(event)
                val from = source
                val into = destination()
                clear()
                if (from == null || into == null) return false
                currentMove(from, into.path + "/" + from.path.substringAfterLast('/'))
                return true
            }
        }
    }
    return onGloballyPositioned { scroll.coordinates = it }.dragAndDropTarget(
        shouldStartDragAndDrop = { (it.toAndroidDragEvent().localState as? OrgDrag)?.kind == "file" },
        target = target,
    )
}
