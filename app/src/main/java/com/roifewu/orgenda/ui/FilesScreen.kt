@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.roifewu.orgenda.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.strictText
import com.roifewu.orgenda.data.*
import com.roifewu.orgenda.platform.SyncWorker
import kotlinx.coroutines.*

internal fun visibleFolderFiles(files: List<FileEntity>, folder: String): List<FileEntity> {
    val prefix = if (folder.isEmpty()) "" else "$folder/"
    val descendants = files.filter {
        !it.trashed &&
            it.path != "config.json" &&
            !it.path.split('/').any { part -> part.startsWith('.') } &&
            it.path.startsWith(prefix)
    }
    val explicitFolders = descendants.filter { it.directory }.mapTo(mutableSetOf()) { it.path }
    return descendants
        .filter { file ->
            val components = file.path.removePrefix(prefix).split('/')
            var ancestor = folder
            components.dropLast(1).none { component ->
                ancestor = if (ancestor.isEmpty()) component else "$ancestor/$component"
                ancestor in explicitFolders
            }
        }
        .sortedWith(compareBy<FileEntity> { !it.directory }.thenBy { it.path.lowercase() })
}

internal fun deletedFileRoots(
    files: List<FileEntity>,
    operations: List<OperationEntity>,
): List<FileEntity> {
    val deleted = files.filter { it.trashed }
    val pendingTrash = operations.filter { it.kind == "trash" }.groupBy { it.fileId }
    return deleted
        .filter { file ->
            // A separately deleted item keeps its own restore action, even when its
            // original path happens to sit below another deleted folder.
            file.id in pendingTrash ||
                deleted.none { parent ->
                    parent.id != file.id &&
                        parent.directory &&
                        parent.workspaceId == file.workspaceId &&
                        file.remotePath.startsWith(parent.remotePath + "/") &&
                        (parent.remotePath.startsWith(".orgenda-trash/") ||
                            pendingTrash[parent.id]?.any { it.from == parent.remotePath } == true)
                }
        }
        .sortedByDescending { it.deletedAt }
}

@Composable
internal fun FileFolderList(
    folder: String,
    modifier: Modifier = Modifier,
    snapshot: WorkspaceSnapshot? = null,
    targeted: (String?) -> Unit = {},
    move: (FileEntity, String) -> Unit = { _, _ -> },
    content: LazyListScope.(String) -> Unit,
) {
    val states = rememberSaveableStateHolder()
    PageTransition(
        targetState = folder,
        modifier = modifier,
        direction = { previous, next ->
            when {
                previous == next -> 0
                previous.isEmpty() || next.startsWith("$previous/") -> 1
                next.isEmpty() || previous.startsWith("$next/") -> -1
                else -> 0
            }
        },
    ) { shownFolder ->
        states.SaveableStateProvider(shownFolder) {
            val list = rememberLazyListState()
            LazyColumn(
                Modifier.fillMaxSize().testTag("files.browser").then(
                    if (snapshot != null) Modifier.fileListDrop(list, snapshot, targeted, move) else Modifier
                ),
                state = list,
                contentPadding = PaddingValues(bottom = UiSpacing.listBottomWithFab),
                content = { content(shownFolder) },
            )
        }
    }
}

@Composable
internal fun FileSwipeActions(
    file: FileEntity,
    pin: () -> Unit,
    move: () -> Unit,
    delete: () -> Unit,
    content: @Composable () -> Unit,
) {
    SwipeActionsRow(
        leading =
            listOf(
                SwipeAction(
                    "files.swipe.pin",
                    tr(if (file.pinned) "unpin" else "pin"),
                    Icons.Default.PushPin,
                    invoke = pin,
                )
            ),
        trailing =
            listOf(
                SwipeAction(
                    "files.swipe.move",
                    tr("swipe_move"),
                    Icons.Default.Folder,
                    invoke = move,
                ),
                SwipeAction(
                    "files.swipe.delete",
                    tr("swipe_delete"),
                    Icons.Default.DeleteOutline,
                    destructive = true,
                    invoke = delete,
                ),
            ),
        modifier = Modifier.testTag("files.row.${file.path}"),
        content = content,
    )
}

@Stable
internal class FilesScreenState(folder: String = "", create: String? = null) {
    var folder by mutableStateOf(folder)
    var create by mutableStateOf(create)
    var trash by mutableStateOf(false)
}

@Composable
internal fun rememberFilesScreenState(workspaceId: String?): FilesScreenState =
    rememberSaveable(
        workspaceId,
        saver =
            Saver<FilesScreenState, List<String>>(
                save = { listOf(it.folder, it.create.orEmpty()) },
                restore = { FilesScreenState(it[0], it[1].takeIf { kind -> kind.isNotEmpty() }) },
            ),
    ) {
        FilesScreenState()
    }

internal fun acceptsFileDrop(
    snapshot: WorkspaceSnapshot,
    payload: Any,
    destination: String,
): Boolean {
    val source = payload as? FileEntity ?: return false
    return source.workspaceId == snapshot.workspace?.id &&
        !source.trashed &&
        snapshot.files.any {
            it.id == source.id &&
                it.path == source.path &&
                it.generation == source.generation &&
                !it.trashed
        } &&
        source.path.substringBeforeLast('/', "") != destination &&
        (!source.directory ||
            destination != source.path && !destination.startsWith(source.path + "/"))
}

@Composable
internal fun FilesTopAppBar(
    model: OrgendaViewModel,
    snapshot: WorkspaceSnapshot,
    state: FilesScreenState,
    settings: () -> Unit,
) {
    var parentTargeted by remember(state.folder) { mutableStateOf(false) }
    TopAppBar(
        title = {
            Row(
                modifier = Modifier.orgDrop("file", accepts = { acceptsFileDrop(snapshot, it, state.folder) }) { payload ->
                    val source = payload as FileEntity
                    model.moveFile(source, (if (state.folder.isEmpty()) "" else "${state.folder}/") + source.path.substringAfterLast('/'))
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm),
            ) {
                Text(
                    state.folder.ifEmpty { tr("files") },
                    Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (snapshot.syncing)
                    CircularProgressIndicator(Modifier.size(UiSizes.progressIndicator), strokeWidth = UiSizes.progressStroke)
                else
                    Icon(
                        when {
                            snapshot.syncError != null -> Icons.Default.ErrorOutline
                            snapshot.pending > 0 -> Icons.Default.CloudUpload
                            else -> Icons.Default.CheckCircle
                        },
                        tr(
                            when {
                                snapshot.syncError != null -> "operation_failed"
                                snapshot.pending > 0 -> "pending"
                                else -> "synced"
                            }
                        ),
                        Modifier.size(UiSizes.iconMedium),
                        tint =
                            if (snapshot.syncError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        },
        navigationIcon = {
            if (state.folder.isNotEmpty())
                IconButton(
                    onClick = { state.folder = state.folder.substringBeforeLast('/', "") },
                    modifier = Modifier.testTag("files.parent").orgDrop(
                        "file",
                        targeted = { parentTargeted = it },
                        accepts = { acceptsFileDrop(snapshot, it, state.folder.substringBeforeLast('/', "")) },
                    ) { payload ->
                        val source = payload as FileEntity
                        val parent = state.folder.substringBeforeLast('/', "")
                        model.moveFile(source, (if (parent.isEmpty()) "" else "$parent/") + source.path.substringAfterLast('/'))
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (parentTargeted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("back"))
                }
        },
        actions = {
            IconButton(onClick = settings) { Icon(Icons.Default.Settings, tr("settings")) }
            IconButton(onClick = { model.sync() }, enabled = !snapshot.syncing) {
                Icon(Icons.Default.Sync, tr("sync"))
            }
            IconButton(onClick = { state.trash = true }) {
                Icon(Icons.Default.DeleteOutline, tr("trash"))
            }
        },
    )
}

@Composable
private fun FilesAddButton(
    model: OrgendaViewModel,
    snapshot: WorkspaceSnapshot,
    state: FilesScreenState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var addMenu by remember(snapshot.workspace?.id) { mutableStateOf(false) }
    val import =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                model.action {
                    val name =
                        context.contentResolver
                            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                            ?: "Imported.org"
                    val path = (if (state.folder.isEmpty()) "" else "${state.folder}/") + name
                    require(
                        name.endsWith(".org") ||
                            name.endsWith(".org_archive") ||
                            name.endsWith(".md")
                    ) {
                        "Choose an Org or Markdown document"
                    }
                    val bytes =
                        context.contentResolver.openInputStream(uri)?.use {
                            it.readNBytes(64 * 1024 * 1024 + 1)
                        } ?: error("Document unavailable")
                    require(bytes.size <= 64 * 1024 * 1024)
                    model.repository.create(path, bytes.strictText())
                }
        }
    Box(modifier) {
        FloatingActionButton(
            onClick = { addMenu = true },
            shape = CircleShape,
            modifier = Modifier.testTag("files.add"),
            elevation = subtleFabElevation(),
        ) {
            Icon(Icons.Default.Add, tr("add"))
        }
        DropdownMenu(addMenu, { addMenu = false }) {
            DropdownMenuItem(
                text = { Text(tr("new_file")) },
                onClick = {
                    addMenu = false
                    state.create = "new_file"
                },
            )
            DropdownMenuItem(
                text = { Text(tr("new_folder")) },
                onClick = {
                    addMenu = false
                    state.create = "new_folder"
                },
            )
            DropdownMenuItem(
                text = { Text(tr("import_document")) },
                onClick = {
                    addMenu = false
                    import.launch(arrayOf("text/*", "application/octet-stream"))
                },
            )
        }
    }
}

@Composable
internal fun FilesScreen(
    model: OrgendaViewModel,
    snapshot: WorkspaceSnapshot,
    initialFolder: String? = null,
    state: FilesScreenState = rememberFilesScreenState(snapshot.workspace?.id),
    onFolderOpened: () -> Unit = {},
) {
    var folder by state::folder
    androidx.activity.compose.BackHandler(LocalPageActive.current && folder.isNotEmpty()) {
        folder = folder.substringBeforeLast('/', "")
    }
    val fileSelectionSaver =
        remember(model) {
            Saver<FileEntity?, List<String>>(
                save = { file ->
                    file?.let {
                        listOf(
                            it.workspaceId,
                            it.id,
                            it.path,
                            it.generation.toString(),
                            it.contentBlob.orEmpty(),
                        )
                    }
                },
                restore = { saved ->
                    model.workspace.value
                        .takeIf { it.workspace?.id == saved[0] }
                        ?.files
                        ?.firstOrNull {
                            it.id == saved[1] &&
                                it.path == saved[2] &&
                                it.generation.toString() == saved[3] &&
                                it.contentBlob.orEmpty() == saved[4] &&
                                !it.trashed
                        }
                },
            )
        }
    var create by state::create
    var move by
        rememberSaveable(snapshot.workspace?.id, stateSaver = fileSelectionSaver) {
            mutableStateOf<FileEntity?>(null)
        }
    var delete by remember(snapshot.workspace?.id) { mutableStateOf<FileEntity?>(null) }
    var trash by state::trash
    var preview by remember(snapshot.workspace?.id) { mutableStateOf<FileEntity?>(null) }
    var previewText by remember { mutableStateOf("") }
    var restoring by remember(snapshot.workspace?.id) { mutableStateOf(false) }
    var restoreError by remember(snapshot.workspace?.id) { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val pinPreferences = remember {
        context.getSharedPreferences("file-shortcuts", android.content.Context.MODE_PRIVATE)
    }
    val pinOrderKey = "pinned:${snapshot.workspace?.id}"
    var pinOrder by
        remember(snapshot.workspace?.id) {
            mutableStateOf(pinPreferences.getString(pinOrderKey, "").orEmpty().split('\n'))
        }
    fun togglePin(file: FileEntity) {
        val nextOrder =
            if (file.pinned) pinOrder.filter { it != file.id }
            else listOf(file.id) + pinOrder.filter { it != file.id }
        pinOrder = nextOrder
        pinPreferences.edit().putString(pinOrderKey, nextOrder.joinToString("\n")).apply()
        model.action { model.repository.remember(file.id, pin = !file.pinned) }
    }
    LaunchedEffect(initialFolder, snapshot.workspace?.id) {
        initialFolder?.let {
            folder = it
            onFolderOpened()
        }
    }
    LaunchedEffect(snapshot.files, folder) {
        while (
            folder.isNotEmpty() &&
                snapshot.files.none {
                    !it.trashed &&
                        (it.path == folder && it.directory || it.path.startsWith("$folder/"))
                }
        ) {
            folder = folder.substringBeforeLast('/', "")
        }
    }
    LaunchedEffect(preview) {
        previewText = ""
        try {
            previewText =
                preview?.takeUnless { it.directory }?.let { model.repository.text(it).take(3000) }
                    ?: ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            previewText = e.message ?: context.label("operation_failed")
        }
    }
    val visible =
        snapshot.files.filter {
            !it.trashed &&
                !it.path.split('/').any { part -> part.startsWith('.') } &&
                it.path != "config.json"
        }
    fun open(file: FileEntity) {
        if (file.directory) folder = file.path else model.open(file)
    }
    var dropFolder by remember(snapshot.workspace?.id, folder) { mutableStateOf<String?>(null) }
    val row: @Composable (FileEntity, Boolean) -> Unit = { file, showPath ->
        val targeted = dropFolder == file.path
        var menu by remember(file.id) { mutableStateOf(false) }
        FileSwipeActions(file, { togglePin(file) }, { move = file }, { delete = file }) {
            Box {
                ListItem(
                    colors =
                        ListItemDefaults.colors(
                            containerColor =
                                if (targeted) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface
                        ),
                    headlineContent = { Text(file.path.substringAfterLast('/')) },
                    supportingContent = {
                        Column {
                            if (showPath) Text(file.path)
                            else if (file.directory) Text(tr("folder"))
                            else
                                snapshot.documents[file.path]?.let {
                                    Text(it.headings.size.toString() + " " + tr("headings"))
                                }
                            if (file.conflict)
                                Text(tr("conflicts"), color = MaterialTheme.colorScheme.error)
                            else if (file.draftBlob != null) Text(tr("pending"))
                        }
                    },
                    leadingContent = {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color =
                                if (file.directory) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor =
                                if (file.directory) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Icon(
                                if (file.directory) Icons.Default.Folder
                                else Icons.Default.Description,
                                tr("drag_file"),
                                Modifier.size(UiSizes.touchTarget).padding(UiSpacing.md),
                            )
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (file.pinned)
                                Icon(Icons.Default.PushPin, tr("pinned"), Modifier.size(UiSizes.iconSmall))
                            // Keep secondary actions anchored to the overflow button; long press moves the row.
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        tr("actions"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(tr("open")) },
                                        onClick = {
                                            menu = false
                                            open(file)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(tr("preview")) },
                                        onClick = {
                                            menu = false
                                            preview = file
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(tr(if (file.pinned) "unpin" else "pin")) },
                                        onClick = {
                                            menu = false
                                            togglePin(file)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(tr("move")) },
                                        onClick = {
                                            menu = false
                                            move = file
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(tr("delete")) },
                                        onClick = {
                                            menu = false
                                            delete = file
                                        },
                                    )
                                }
                            }
                        }
                    },
                    modifier =
                        Modifier                            .orgDrag("file", file)
                            .clickable { open(file) }
                            .semantics {
                                customActions =
                                    listOf(
                                        CustomAccessibilityAction(
                                            context.label(if (file.pinned) "unpin" else "pin")
                                        ) {
                                            togglePin(file)
                                            true
                                        },
                                        CustomAccessibilityAction(context.label("move")) {
                                            move = file
                                            true
                                        },
                                        CustomAccessibilityAction(context.label("delete")) {
                                            delete = file
                                            true
                                        },
                                    )
                            },
                )
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = snapshot.syncing,
            onRefresh = { model.sync() },
            modifier = Modifier.fillMaxSize(),
        ) {
            key(snapshot.workspace?.id) {
                FileFolderList(
                    folder, Modifier.fillMaxSize(), snapshot,
                    targeted = { dropFolder = it },
                    move = { file, destination -> model.moveFile(file, destination) },
                ) { shownFolder ->
                    if (shownFolder.isEmpty()) {
                        val pinned =
                            visible
                                .filter { it.pinned }
                                .sortedBy {
                                    pinOrder.indexOf(it.id).takeIf { index -> index >= 0 }
                                        ?: Int.MAX_VALUE
                                }
                        item { ListSectionHeader(tr("pinned"), pinned.size) }
                        if (pinned.isEmpty())
                            item {
                                Text(
                                    tr("pinned_hint"),
                                    Modifier.padding(
                                        horizontal = UiSpacing.edge,
                                        vertical = UiSpacing.xs + UiSpacing.supportingTextExtraVertical,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        items(pinned, key = { "pin:${it.id}" }) { row(it, true) }
                        val recent =
                            visible
                                .filter { !it.directory && it.openedAt > 0 }
                                .sortedByDescending { it.openedAt }
                                .take(10)
                        item { ListSectionHeader(tr("recent"), recent.size) }
                        if (recent.isEmpty())
                            item {
                                Text(
                                    tr("recent_files_hint"),
                                    Modifier.padding(
                                        horizontal = UiSpacing.edge,
                                        vertical = UiSpacing.xs + UiSpacing.supportingTextExtraVertical,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        items(recent, key = { "recent:${it.id}" }) { row(it, true) }
                        item { ListSectionHeader(tr("all_files")) }
                    }
                    val children = visibleFolderFiles(visible, shownFolder)
                    if (children.isEmpty())
                        item {
                            Text(
                                tr("no_files"),
                                Modifier.padding(horizontal = UiSpacing.edge, vertical = UiSpacing.xl),
                            )
                        }
                    items(children, key = { it.id }) {
                        row(
                            it,
                            shownFolder.isNotEmpty() ||
                                it.path.substringBeforeLast('/', "") != shownFolder,
                        )
                    }
                }
            }
        }
        FilesAddButton(model, snapshot, state, Modifier.align(Alignment.BottomEnd).padding(UiSpacing.edge))
    }
    create?.let { kind ->
        TextPrompt(
            kind,
            initial = if (folder.isEmpty()) "" else "$folder/",
            onDismiss = { create = null },
        ) { path ->
            model.action { model.repository.create(path, directory = kind == "new_folder") }
            create = null
        }
    }
    move?.let { file ->
        MoveFileSheet(snapshot, file, { move = null }) { path ->
            withContext(Dispatchers.IO) { model.moveFileNow(file, path) }
            SyncWorker.enqueue(model.app, file.workspaceId)
        }
    }
    delete?.let { file ->
        ModalPresence()
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text(tr("delete")) },
            text = { Text(file.path + "\n\n" + tr("confirm_delete")) },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        model.moveFile(file, file.path, trash = true)
                        delete = null
                    }
                ) {
                    Text(tr("delete"))
                }
            },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.medium, onClick = { delete = null }) { Text(tr("cancel")) } },
        )
    }
    preview?.let { file ->
        ModalPresence()
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(file.path) },
            text = {
                Text(
                    if (file.directory)
                        visibleFolderFiles(visible, file.path)
                            .take(10)
                            .joinToString("\n") { it.path }
                            .ifEmpty { context.label("no_files") }
                    else previewText,
                    Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        preview = null
                        open(file)
                    }
                ) {
                    Text(tr("open"))
                }
            },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.medium, onClick = { preview = null }) { Text(tr("close")) } },
        )
    }
    if (trash) {
        ModalPresence()
        AlertDialog(
            onDismissRequest = { if (!restoring) trash = false },
            title = { Text(tr("trash")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 500.dp)) {
                    if (restoring) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    restoreError?.let { message ->
                        item {
                            Text(
                                message,
                                Modifier.padding(UiSpacing.edge),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    val deleted = deletedFileRoots(snapshot.files, snapshot.operations)
                    if (deleted.isEmpty())
                        item { Text(tr("no_deleted_items"), Modifier.padding(UiSpacing.edge)) }
                    items(deleted, key = { it.id }) { file ->
                        ListItem(
                            headlineContent = { Text(file.path.substringAfterLast('/')) },
                            supportingContent = { Text(file.path) },
                            trailingContent = {
                                TextButton(
                                    shape = MaterialTheme.shapes.medium,
                                    enabled = !restoring,
                                    onClick = {
                                        restoring = true
                                        restoreError = null
                                        coroutineScope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    require(
                                                        model.workspace.value.workspace?.id ==
                                                            file.workspaceId
                                                    ) {
                                                        "Workspace changed"
                                                    }
                                                    model.repository.restore(file)
                                                }
                                                SyncWorker.enqueue(model.app, file.workspaceId)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                restoreError =
                                                    e.message ?: context.label("operation_failed")
                                            } finally {
                                                restoring = false
                                            }
                                        }
                                    },
                                ) {
                                    Text(tr("restore"))
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.medium, onClick = { trash = false }, enabled = !restoring) { Text(tr("close")) }
            },
        )
    }
}
