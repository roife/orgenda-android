@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.roifewu.orgenda.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import com.roifewu.orgenda.core.strictText
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.data.*
import kotlinx.coroutines.*
import com.roifewu.orgenda.platform.SyncWorker

internal fun visibleFolderFiles(files: List<FileEntity>, folder: String): List<FileEntity> {
    val prefix = if (folder.isEmpty()) "" else "$folder/"
    val descendants = files.filter { !it.trashed && it.path != "config.json" && !it.path.split('/').any { part -> part.startsWith('.') } && it.path.startsWith(prefix) }
    val explicitFolders = descendants.filter { it.directory }.mapTo(mutableSetOf()) { it.path }
    return descendants.filter { file ->
        val components = file.path.removePrefix(prefix).split('/')
        var ancestor = folder
        components.dropLast(1).none { component ->
            ancestor = if (ancestor.isEmpty()) component else "$ancestor/$component"
            ancestor in explicitFolders
        }
    }.sortedWith(compareBy<FileEntity> { !it.directory }.thenBy { it.path.lowercase() })
}

internal fun deletedFileRoots(files: List<FileEntity>, operations: List<OperationEntity>): List<FileEntity> {
    val deleted = files.filter { it.trashed }
    val pendingTrash = operations.filter { it.kind == "trash" }.groupBy { it.fileId }
    return deleted.filter { file ->
        // A separately deleted item keeps its own restore action, even when its
        // original path happens to sit below another deleted folder.
        file.id in pendingTrash || deleted.none { parent ->
            parent.id != file.id && parent.directory && parent.workspaceId == file.workspaceId &&
                file.remotePath.startsWith(parent.remotePath + "/") &&
                (parent.remotePath.startsWith(".orgenda-trash/") || pendingTrash[parent.id]?.any { it.from == parent.remotePath } == true)
        }
    }.sortedByDescending { it.deletedAt }
}

@Composable internal fun FileFolderList(folder: String, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    val states = rememberSaveableStateHolder()
    states.SaveableStateProvider(folder) {
        LazyColumn(modifier.testTag("files.browser"), contentPadding = PaddingValues(bottom = 32.dp), content = content)
    }
}

@Composable fun FilesScreen(model: OrgendaViewModel, snapshot: WorkspaceSnapshot, initialFolder: String? = null, onFolderOpened: () -> Unit = {}) {
    var folder by rememberSaveable(snapshot.workspace?.id) { mutableStateOf("") }
    androidx.activity.compose.BackHandler(folder.isNotEmpty()) { folder = folder.substringBeforeLast('/', "") }
    val fileSelectionSaver = remember(model) {
        Saver<FileEntity?, List<String>>(
            save = { file -> file?.let { listOf(it.workspaceId, it.id, it.path, it.generation.toString(), it.contentBlob.orEmpty()) } },
            restore = { saved -> model.workspace.value.takeIf { it.workspace?.id == saved[0] }?.files?.firstOrNull {
                it.id == saved[1] && it.path == saved[2] && it.generation.toString() == saved[3] && it.contentBlob.orEmpty() == saved[4] && !it.trashed
            } }
        )
    }
    var create by rememberSaveable(snapshot.workspace?.id) { mutableStateOf<String?>(null) }
    var move by rememberSaveable(snapshot.workspace?.id, stateSaver = fileSelectionSaver) { mutableStateOf<FileEntity?>(null) }
    var delete by remember(snapshot.workspace?.id) { mutableStateOf<FileEntity?>(null) }
    var trash by remember(snapshot.workspace?.id) { mutableStateOf(false) }
    var preview by remember(snapshot.workspace?.id) { mutableStateOf<FileEntity?>(null) }
    var previewText by remember { mutableStateOf("") }
    var restoring by remember(snapshot.workspace?.id) { mutableStateOf(false) }
    var restoreError by remember(snapshot.workspace?.id) { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val pinPreferences = remember { context.getSharedPreferences("file-shortcuts", android.content.Context.MODE_PRIVATE) }
    val pinOrderKey = "pinned:${snapshot.workspace?.id}"
    var pinOrder by remember(snapshot.workspace?.id) { mutableStateOf(pinPreferences.getString(pinOrderKey, "").orEmpty().split('\n')) }
    fun togglePin(file: FileEntity) {
        val nextOrder = if (file.pinned) pinOrder.filter { it != file.id } else listOf(file.id) + pinOrder.filter { it != file.id }
        pinOrder = nextOrder
        pinPreferences.edit().putString(pinOrderKey, nextOrder.joinToString("\n")).apply()
        model.action { model.repository.remember(file.id, pin = !file.pinned) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.action {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Imported.org"
        val path = (if (folder.isEmpty()) "" else "$folder/") + name
        require(name.endsWith(".org") || name.endsWith(".org_archive") || name.endsWith(".md")) { "Choose an Org or Markdown document" }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(64 * 1024 * 1024 + 1) } ?: error("Document unavailable")
        require(bytes.size <= 64 * 1024 * 1024)
        model.repository.create(path, bytes.strictText())
    } }
    LaunchedEffect(initialFolder, snapshot.workspace?.id) {
        initialFolder?.let { folder = it; onFolderOpened() }
    }
    LaunchedEffect(snapshot.files, folder) {
        while (folder.isNotEmpty() && snapshot.files.none { !it.trashed && (it.path == folder && it.directory || it.path.startsWith("$folder/")) }) {
            folder = folder.substringBeforeLast('/', "")
        }
    }
    LaunchedEffect(preview) {
        previewText = ""
        try { previewText = preview?.takeUnless { it.directory }?.let { model.repository.text(it).take(3000) } ?: "" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { previewText = e.message ?: context.label("operation_failed") }
    }
    val visible = snapshot.files.filter { !it.trashed && !it.path.split('/').any { part -> part.startsWith('.') } && it.path != "config.json" }
    fun open(file: FileEntity) { if (file.directory) folder = file.path else model.open(file) }
    fun drop(payload: Any, folder: String) {
        val source = payload as? FileEntity ?: return
        if (source.workspaceId != snapshot.workspace?.id || source.path.substringBeforeLast('/', "") == folder) return
        model.moveFile(source, (if (folder.isEmpty()) "" else "$folder/") + source.path.substringAfterLast('/'))
    }
    val row: @Composable (FileEntity, Boolean) -> Unit = { file, showPath ->
        var targeted by remember { mutableStateOf(false) }
        var menu by remember(file.id) { mutableStateOf(false) }
        val currentFile by rememberUpdatedState(file)
        val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> togglePin(currentFile)
                SwipeToDismissBoxValue.EndToStart -> delete = currentFile
                else -> Unit
            }
            false
        })
        SwipeToDismissBox(state = swipe, backgroundContent = {
            val pinAction = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Surface(color = if (pinAction) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.padding(horizontal = 24.dp), contentAlignment = if (pinAction) Alignment.CenterStart else Alignment.CenterEnd) {
                    Icon(if (pinAction) Icons.Default.PushPin else Icons.Default.DeleteOutline, tr(if (pinAction) if (file.pinned) "unpin" else "pin" else "delete"))
                }
            }
        }) {
        Box {
            ListItem(colors = ListItemDefaults.colors(containerColor = if (targeted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface), headlineContent = { Text(file.path.substringAfterLast('/')) },
                supportingContent = { Column {
                    if (showPath || file.path.substringBeforeLast('/', "") != folder) Text(file.path)
                    else if (file.directory) Text(tr("folder"))
                    else snapshot.documents[file.path]?.let { Text(it.headings.size.toString() + " " + tr("headings")) }
                    if (file.conflict) Text(tr("conflicts"), color = MaterialTheme.colorScheme.error) else if (file.draftBlob != null) Text(tr("pending"))
                } },
                leadingContent = { Icon(if (file.directory) Icons.Default.Folder else Icons.Default.Description, tr("drag_file"), Modifier.size(48.dp).orgDrag("file", file).padding(12.dp)) },
                trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                    if (file.pinned) Icon(Icons.Default.PushPin, tr("pinned"), Modifier.size(16.dp))
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, tr("actions")) }
                } },
                modifier = (if (file.directory) Modifier.orgDrop("file", { targeted = it }) { drop(it, file.path) } else Modifier).combinedClickable(onClick = { open(file) }, onLongClick = { menu = true }).semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(context.label(if (file.pinned) "unpin" else "pin")) { togglePin(file); true },
                        CustomAccessibilityAction(context.label("move")) { move = file; true },
                        CustomAccessibilityAction(context.label("delete")) { delete = file; true }
                    )
                })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(tr("open")) }, onClick = { menu = false; open(file) })
                DropdownMenuItem(text = { Text(tr("preview")) }, onClick = { menu = false; preview = file })
                DropdownMenuItem(text = { Text(tr(if (file.pinned) "unpin" else "pin")) }, onClick = { menu = false; togglePin(file) })
                DropdownMenuItem(text = { Text(tr("move")) }, onClick = { menu = false; move = file })
                DropdownMenuItem(text = { Text(tr("delete")) }, onClick = { menu = false; delete = file })
            }
        }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).orgDrop("file") { drop(it, folder) }, verticalAlignment = Alignment.CenterVertically) {
            if (folder.isNotEmpty()) IconButton(onClick = { folder = folder.substringBeforeLast('/', "") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("back")) }
            Text(folder.ifEmpty { snapshot.workspace?.name ?: "Orgenda" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (snapshot.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(when { snapshot.syncError != null -> Icons.Default.ErrorOutline; snapshot.pending > 0 -> Icons.Default.CloudUpload; else -> Icons.Default.CheckCircle }, tr(when { snapshot.syncError != null -> "operation_failed"; snapshot.pending > 0 -> "pending"; else -> "synced" }), Modifier.size(20.dp), tint = if (snapshot.syncError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            var addMenu by remember { mutableStateOf(false) }
            Box { IconButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, tr("add")) }
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem(text = { Text(tr("new_file")) }, onClick = { addMenu = false; create = "new_file" })
                    DropdownMenuItem(text = { Text(tr("new_folder")) }, onClick = { addMenu = false; create = "new_folder" })
                    DropdownMenuItem(text = { Text(tr("import_document")) }, onClick = { addMenu = false; import.launch(arrayOf("text/*", "application/octet-stream")) })
                }
            }
            IconButton(onClick = { trash = true }) { Icon(Icons.Default.DeleteOutline, tr("trash")) }
        }
        PullToRefreshBox(isRefreshing = snapshot.syncing, onRefresh = { model.sync() }, modifier = Modifier.weight(1f)) {
        key(snapshot.workspace?.id) { FileFolderList(folder, Modifier.fillMaxSize()) {
            if (folder.isEmpty()) {
                val pinned = visible.filter { it.pinned }.sortedBy { pinOrder.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                item { Text(tr("pinned"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp)) }
                if (pinned.isEmpty()) item { Text(tr("pinned_hint"), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(pinned, key = { "pin:${it.id}" }) { row(it, true) }
                val recent = visible.filter { !it.directory && it.openedAt > 0 }.sortedByDescending { it.openedAt }.take(10)
                item { Text(tr("recent"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp)) }
                if (recent.isEmpty()) item { Text(tr("recent_files_hint"), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(recent, key = { "recent:${it.id}" }) { row(it, true) }
                item { Text(tr("all_files"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp)) }
            }
            val children = visibleFolderFiles(visible, folder)
            if (children.isEmpty()) item { Text(tr("no_files"), Modifier.padding(24.dp)) }
            items(children, key = { it.id }) { row(it, folder.isNotEmpty()) }
        } }
        }
    }
    create?.let { kind -> TextPrompt(kind, initial = if (folder.isEmpty()) "" else "$folder/", onDismiss = { create = null }) { path -> model.action { model.repository.create(path, directory = kind == "new_folder") }; create = null } }
    move?.let { file -> MoveFileSheet(snapshot, file, { move = null }) { path -> withContext(Dispatchers.IO) { model.moveFileNow(file, path) }; SyncWorker.enqueue(model.app, file.workspaceId) } }
    delete?.let { file -> ModalPresence(); AlertDialog(onDismissRequest = { delete = null }, title = { Text(tr("delete")) }, text = { Text(file.path + "\n\n" + tr("confirm_delete")) },
        confirmButton = { TextButton(onClick = { model.moveFile(file, file.path, trash = true); delete = null }) { Text(tr("delete")) } }, dismissButton = { TextButton(onClick = { delete = null }) { Text(tr("cancel")) } }) }
    preview?.let { file -> ModalPresence(); AlertDialog(onDismissRequest = { preview = null }, title = { Text(file.path) }, text = { Text(if (file.directory) visibleFolderFiles(visible, file.path).take(10).joinToString("\n") { it.path }.ifEmpty { context.label("no_files") } else previewText, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { preview = null; open(file) }) { Text(tr("open")) } }, dismissButton = { TextButton(onClick = { preview = null }) { Text(tr("close")) } }) }
    if (trash) { ModalPresence(); AlertDialog(onDismissRequest = { if (!restoring) trash = false }, title = { Text(tr("trash")) }, text = { LazyColumn(Modifier.heightIn(max = 500.dp)) {
        if (restoring) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        restoreError?.let { message -> item { Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
        val deleted = deletedFileRoots(snapshot.files, snapshot.operations)
        if (deleted.isEmpty()) item { Text(tr("no_deleted_items"), Modifier.padding(16.dp)) }
        items(deleted, key = { it.id }) { file -> ListItem(headlineContent = { Text(file.path.substringAfterLast('/')) }, supportingContent = { Text(file.path) }, trailingContent = { TextButton(enabled = !restoring, onClick = {
            restoring = true; restoreError = null
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        require(model.workspace.value.workspace?.id == file.workspaceId) { "Workspace changed" }
                        model.repository.restore(file)
                    }
                    SyncWorker.enqueue(model.app, file.workspaceId)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { restoreError = e.message ?: context.label("operation_failed") }
                finally { restoring = false }
            }
        }) { Text(tr("restore")) } }) }
    } }, confirmButton = { TextButton(onClick = { trash = false }, enabled = !restoring) { Text(tr("close")) } }) }
}
