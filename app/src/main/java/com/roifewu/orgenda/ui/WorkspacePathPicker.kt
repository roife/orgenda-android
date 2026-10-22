@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roifewu.orgenda.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.core.safePath
import com.roifewu.orgenda.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun WorkspacePathPicker(
    snapshot: WorkspaceSnapshot,
    directory: Boolean,
    initial: String = "",
    exclude: String? = null,
    close: () -> Unit,
    select: (String) -> Unit,
) {
    ModalPresence()
    var folder by
        rememberSaveable(initial, exclude) {
            mutableStateOf(
                initial.takeIf { path ->
                    path.isEmpty() ||
                        snapshot.files.any { it.directory && it.path == path && !it.trashed }
                } ?: initial.substringBeforeLast('/', "")
            )
        }
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (folder.isNotEmpty())
                IconButton(onClick = { folder = folder.substringBeforeLast('/', "") }) {
                    Icon(Icons.Default.ArrowUpward, tr("up"))
                }
            Text(folder.ifEmpty { snapshot.workspace?.name ?: "Orgenda" }, Modifier.weight(1f))
            if (directory) TextButton(onClick = { select(folder) }) { Text(tr("select_folder")) }
            TextButton(onClick = close) { Text(tr("cancel")) }
        }
        LazyColumn(Modifier.fillMaxHeight(0.7f)) {
            val children =
                snapshot.files
                    .filter {
                        !it.trashed &&
                            (!directory || it.directory) &&
                            it.path.substringBeforeLast('/', "") == folder &&
                            (exclude == null ||
                                it.path != exclude && !it.path.startsWith("$exclude/"))
                    }
                    .sortedWith(
                        compareBy<FileEntity> { !it.directory }.thenBy { it.path.lowercase() }
                    )
            if (children.isEmpty()) item { Text(tr("no_files"), Modifier.padding(24.dp)) }
            items(children, key = { it.id }) { file ->
                ListItem(
                    headlineContent = { Text(file.path.substringAfterLast('/')) },
                    leadingContent = {
                        Icon(
                            if (file.directory) Icons.Default.Folder else Icons.Default.Description,
                            null,
                        )
                    },
                    modifier =
                        Modifier.clickable {
                            if (file.directory) folder = file.path else select(file.path)
                        },
                )
            }
        }
    }
}

@Composable
fun WorkspacePathField(
    value: String,
    label: String,
    snapshot: WorkspaceSnapshot,
    directory: Boolean = false,
    enabled: Boolean = true,
    change: (String) -> Unit,
) {
    var picker by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) picker = false }
    val invalid = runCatching { safePath(value) }.isFailure
    Field(
        value,
        change,
        label,
        invalid = invalid,
        supporting = if (invalid) tr("invalid_destination") else null,
        enabled = enabled,
    )
    TextButton(onClick = { picker = true }, enabled = enabled) {
        Icon(Icons.Default.FolderOpen, null)
        Text(tr("browse"))
    }
    if (picker && enabled)
        WorkspacePathPicker(
            snapshot,
            directory,
            initial = if (directory) value else value.substringBeforeLast('/', ""),
            close = { picker = false },
        ) {
            picker = false
            change(it)
        }
}

@Composable
fun MoveFileSheet(
    snapshot: WorkspaceSnapshot,
    file: FileEntity,
    close: () -> Unit,
    move: suspend (String) -> Unit,
) {
    ModalPresence()
    var name by rememberSaveable(file.id) { mutableStateOf(file.path.substringAfterLast('/')) }
    var folder by
        rememberSaveable(file.id) { mutableStateOf(file.path.substringBeforeLast('/', "")) }
    var picker by rememberSaveable(file.id) { mutableStateOf(false) }
    var moving by remember(file.id) { mutableStateOf(false) }
    var error by rememberSaveable(file.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fallbackError = tr("operation_failed")
    val path = (if (folder.isEmpty()) "" else "$folder/") + name
    val valid =
        name.isNotBlank() &&
            '/' !in name &&
            runCatching { safePath(path) }.isSuccess &&
            snapshot.files.none {
                !it.trashed &&
                    it.id != file.id &&
                    com.roifewu.orgenda.core.foldedPath(it.path) ==
                        com.roifewu.orgenda.core.foldedPath(path)
            }
    AlertDialog(
        onDismissRequest = { if (!moving) close() },
        title = { Text(tr("move")) },
        text = {
            Column {
                Field(
                    name,
                    { name = it },
                    "name",
                    modifier = Modifier.testTag("files.move.name"),
                    supporting = if (!valid) tr("invalid_destination") else null,
                    enabled = !moving,
                )
                TextButton(onClick = { picker = true }, enabled = !moving) {
                    Icon(Icons.Default.Folder, null)
                    Text(folder.ifEmpty { tr("workspace") })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (moving) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    moving = true
                    error = null
                    scope.launch {
                        try {
                            move(path)
                            close()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: fallbackError
                        } finally {
                            moving = false
                        }
                    }
                },
                enabled = !moving && valid && path != file.path,
                modifier = Modifier.testTag("files.move.confirm"),
            ) {
                Text(tr("move"))
            }
        },
        dismissButton = {
            TextButton(
                onClick = close,
                enabled = !moving,
                modifier = Modifier.testTag("files.move.cancel"),
            ) {
                Text(tr("cancel"))
            }
        },
    )
    if (picker)
        WorkspacePathPicker(
            snapshot,
            true,
            folder,
            file.path.takeIf { file.directory },
            { picker = false },
        ) {
            folder = it
            picker = false
        }
}
