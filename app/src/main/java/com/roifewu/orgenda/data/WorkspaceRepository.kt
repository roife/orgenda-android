package com.roifewu.orgenda.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.roifewu.orgenda.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class TrashMetadata(
    val id: String,
    val originalPath: String,
    val title: String,
    val kind: String,
    val deletedAt: Double,
)

@Serializable
private data class RecoveryManifest(
    val files: List<FileEntity>,
    val operations: List<OperationEntity>,
)

data class LocalDocumentEdit(val before: FileEntity, val after: FileEntity)

data class WorkspaceSnapshot(
    val workspace: WorkspaceEntity? = null,
    val files: List<FileEntity> = emptyList(),
    val documents: Map<String, OrgDocument> = emptyMap(),
    val configuration: WorkspaceConfig = WorkspaceConfig.defaults(),
    val operations: List<OperationEntity> = emptyList(),
    val syncing: Boolean = false,
    val error: String? = null,
    val configurationError: String? = null,
    val revision: Long = 0,
    val syncError: String? = null,
    val localDocumentEdits: List<LocalDocumentEdit> = emptyList(),
) {
    val items by lazy { allItems.filter { configuration.agenda.includes(it.path) } }
    val allItems by lazy {
        documents.values
            .flatMap { it.headings }
            .filter {
                it.isTask ||
                    it.stamps.any { stamp -> stamp.keyword != null || stamp.active } ||
                    it.tags.any { tag -> tag in listOf("note", "event", "project", "habit") }
            }
    }
    val journals by lazy {
        documents.values.flatMap { JournalIndex.entries(it, configuration.files.journal) }
    }
    val pending
        get() = files.count { it.draftBlob != null } + operations.size

    val conflicts
        get() = files.filter { it.conflict }
}

class WorkspaceRepository(
    private val context: Context,
    val database: WorkspaceDatabase = WorkspaceDatabase.open(context),
    private val backendOverride: ((WorkspaceEntity) -> StorageBackend)? = null,
) {
    val blobs = BlobStore(File(context.filesDir, "blobs"))
    val engine = NativeOrgEngine()
    private val dao = database.dao()
    private val credentials = Credentials(context)
    private val preferences = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)
    private val stateMutex = Mutex()
    private val syncMutex = Mutex()
    private val mutableState = MutableStateFlow(WorkspaceSnapshot())
    private val localDocumentEdits = ArrayDeque<LocalDocumentEdit>()
    val state = mutableState.asStateFlow()
    private var activeId = preferences.getString("active", null)
    private val backends = mutableMapOf<String, StorageBackend>()
    private val minimumPoll = mutableMapOf<String, Long>()

    suspend fun start() =
        withContext(Dispatchers.IO) {
            if (activeId?.let { dao.workspace(it) } == null) {
                val id = UUID.randomUUID().toString()
                val root = File(context.filesDir, "workspaces/$id").apply { mkdirs() }
                val welcome = File(root, "Welcome.org")
                context.assets.open("Welcome.org").use { input ->
                    welcome.outputStream().use { input.copyTo(it) }
                }
                val workspace = WorkspaceEntity(id, "private", root.path, "Orgenda")
                dao.putWorkspace(workspace)
                setActive(id)
            }
            publish()
            runCatching { sync(activeId!!, pull = true, manual = true) }.onFailure { error(it) }
            Unit
        }

    private fun setActive(id: String) {
        check(preferences.edit().putString("active", id).commit())
        activeId = id
    }

    private fun backend(workspace: WorkspaceEntity): StorageBackend =
        synchronized(backends) {
            backends.getOrPut(workspace.id) {
                backendOverride?.invoke(workspace)
                    ?: when (workspace.kind) {
                        "saf" -> SafBackend(context, Uri.parse(workspace.root))
                        "webdav" ->
                            WebDavBackend(
                                workspace.root,
                                workspace.username,
                                credentials.read(workspace.id),
                            )
                        else -> PrivateFolderBackend(File(workspace.root))
                    }
            }
        }

    suspend fun workspaces() = dao.workspaces()

    suspend fun connectFolder(uri: Uri, name: String) {
        val existing =
            dao.workspaces().firstOrNull { it.kind == "saf" && it.root == uri.toString() }
        connect(
            existing ?: WorkspaceEntity(UUID.randomUUID().toString(), "saf", uri.toString(), name)
        )
    }

    suspend fun connectWebDav(address: String, username: String, password: String) {
        val existing =
            dao.workspaces().firstOrNull {
                it.kind == "webdav" && it.root == address && it.username == username
            }
        val workspace =
            existing
                ?: WorkspaceEntity(
                    UUID.randomUUID().toString(),
                    "webdav",
                    address,
                    Uri.parse(address).host ?: "WebDAV",
                    username,
                )
        val remote = WebDavBackend(address, username, password)
        if (existing == null) remote.ensureRootDuringSetup()
        remote.probe()
        credentials.save(workspace.id, password)
        synchronized(backends) { backends[workspace.id] = remote }
        connect(workspace, verified = true)
    }

    private suspend fun connect(workspace: WorkspaceEntity, verified: Boolean = false) {
        if (!verified) backend(workspace).probe()
        prepareWorkspaceChange(workspace.id)
        stateMutex.withLock {
            dao.putWorkspace(workspace)
            setActive(workspace.id)
        }
        publish()
        sync(workspace.id, pull = true, manual = true)
    }

    suspend fun switchWorkspace(id: String) {
        require(dao.workspace(id) != null)
        prepareWorkspaceChange(id)
        stateMutex.withLock { setActive(id) }
        publish()
        sync(id, true, true)
    }

    suspend fun disconnect() {
        val previous = state.value.workspace ?: return
        val local =
            dao.workspaces().firstOrNull { it.kind == "private" }
                ?: run {
                    val id = UUID.randomUUID().toString()
                    WorkspaceEntity(
                        id,
                        "private",
                        File(context.filesDir, "workspaces/$id").apply { mkdirs() }.path,
                        "Orgenda",
                    )
                }
        connect(local)
        if (previous.kind == "webdav") credentials.remove(previous.id)
        synchronized(backends) { backends.remove(previous.id) }
    }

    private suspend fun recoveryManifest(id: String): RecoveryManifest = database.withTransaction {
        val files = dao.files(id)
        val operations = dao.operations(id)
        RecoveryManifest(
            files.filter { file ->
                file.draftBlob != null ||
                    file.conflict ||
                    operations.any {
                        it.fileId == file.id ||
                            it.directory && file.remotePath.startsWith(it.from + "/")
                    }
            },
            operations,
        )
    }

    private suspend fun prepareWorkspaceChange(next: String) {
        val previous = activeId?.takeIf { it != next } ?: return
        var manifest = recoveryManifest(previous)
        if (manifest.files.isEmpty() && manifest.operations.isEmpty()) return
        runCatching { sync(previous, manual = true) }
            .onFailure { if (it is CancellationException) throw it }
        manifest = recoveryManifest(previous)
        require(
            manifest.files.isEmpty() && manifest.operations.isEmpty() ||
                preferences.getString("exported:$previous", null) ==
                    Json.encodeToString(manifest).sha256()
        ) {
            "Sync pending edits or export a recovery ZIP in Settings before switching workspaces."
        }
    }

    fun error(e: Throwable) {
        mutableState.update {
            it.copy(error = e.message ?: e.javaClass.simpleName, syncing = false)
        }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    suspend fun text(file: FileEntity): String =
        withContext(Dispatchers.IO) { file.contentBlob?.let(blobs::text) ?: "" }

    suspend fun publish() =
        withContext(Dispatchers.IO) {
            stateMutex.withLock {
                val id = activeId ?: return@withLock
                val workspace = dao.workspace(id) ?: return@withLock
                val files = dao.files(id)
                val old = mutableState.value
                var configuration =
                    if (old.workspace?.id == id) old.configuration else WorkspaceConfig.defaults()
                var configurationError: String? = null
                files
                    .firstOrNull { it.path == "config.json" && !it.trashed }
                    ?.contentBlob
                    ?.let { blob ->
                        try {
                            configuration = ConfigurationCodec.decode(blobs.text(blob))
                        } catch (e: Exception) {
                            configurationError = e.message ?: "Invalid configuration"
                        }
                    }
                val docs = mutableMapOf<String, OrgDocument>()
                for (file in
                    files.filter {
                        !it.directory &&
                            !it.trashed &&
                            it.path.endsWith(".org") &&
                            !isRecoveryPath(it.path)
                    }) {
                    file.contentBlob?.let { hash ->
                        try {
                            docs[file.path] =
                                engine.parse(file.path, blobs.text(hash), configuration)
                        } catch (e: Exception) {
                            configurationError = configurationError ?: "${file.path}: ${e.message}"
                        }
                    }
                }
                mutableState.value =
                    WorkspaceSnapshot(
                        workspace,
                        files,
                        docs,
                        configuration,
                        dao.operations(id),
                        old.syncing && old.workspace?.id == id,
                        old.error.takeIf { old.workspace?.id == id },
                        configurationError,
                        old.revision + 1,
                        old.syncError.takeIf { old.workspace?.id == id },
                        localDocumentEdits.toList(),
                    )
            }
        }

    suspend fun save(fileId: String, text: String, expectedBlob: String?): FileEntity =
        withContext(Dispatchers.IO) {
            val blob = blobs.put(text)
            val result = stateMutex.withLock {
                val change = database.withTransaction {
                    val file = dao.file(fileId) ?: error("File no longer exists")
                    require(file.contentBlob == expectedBlob && !file.trashed) {
                        "This file changed while editing. Your draft is still in the editor."
                    }
                    require(dao.files(file.workspaceId).none { it.dependencyId == fileId }) {
                        "Finish syncing the pending heading transfer before changing its destination"
                    }
                    val updated =
                        file.copy(
                            draftBlob = blob.takeUnless { it == file.baseBlob },
                            generation = file.generation + 1,
                        )
                    dao.put(updated)
                    LocalDocumentEdit(file, updated)
                }
                // Publish provenance with the saved version. A conflated StateFlow can
                // skip intermediate versions, so retain a bounded chain of local edits.
                localDocumentEdits.addLast(change)
                while (localDocumentEdits.size > 100) localDocumentEdits.removeFirst()
                change.after
            }
            publish()
            result
        }

    suspend fun create(
        path: String,
        content: String = "",
        directory: Boolean = false,
        expectedWorkspaceId: String? = null,
    ): FileEntity =
        withContext(Dispatchers.IO) {
            safePath(path)
            val ws = activeId ?: error("No workspace")
            val blob = if (directory) null else blobs.put(content)
            val result = stateMutex.withLock {
                database.withTransaction {
                    require(
                        activeId == ws && (expectedWorkspaceId == null || expectedWorkspaceId == ws)
                    ) {
                        "Workspace changed; reopen the editor before saving"
                    }
                    require(dao.path(ws, foldedPath(path)) == null) {
                        "A file or folder already uses that name"
                    }
                    val entry =
                        FileEntity(
                            UUID.randomUUID().toString(),
                            ws,
                            path,
                            draftBlob = blob,
                            directory = directory,
                        )
                    dao.put(entry)
                    if (directory)
                        dao.putOperation(
                            OperationEntity(
                                UUID.randomUUID().toString(),
                                ws,
                                entry.id,
                                "mkdir",
                                "",
                                path,
                                null,
                                null,
                                true,
                                System.currentTimeMillis(),
                            )
                        )
                    entry
                }
            }
            publish()
            result
        }

    suspend fun mutate(item: OrgHeading, action: (OrgDocument, OrgHeading) -> String): FileEntity {
        require(state.value.configurationError == null) {
            "Fix the workspace configuration before editing tasks"
        }
        val file = state.value.files.first { it.path == item.path && !it.trashed }
        val source = text(file)
        val document = engine.parse(file.path, source, state.value.configuration)
        return save(file.id, action(document, engine.resolve(document, item)), file.contentBlob)
    }

    suspend fun capture(
        template: CaptureTemplate,
        rendered: String,
        baseline: String?,
        configurationHash: String,
        workspaceId: String,
        createMissing: Boolean,
        date: java.time.LocalDateTime = java.time.LocalDateTime.now(),
    ): FileEntity {
        require(
            activeId == workspaceId &&
                ConfigurationCodec.encode(state.value.configuration).sha256() == configurationHash
        ) {
            "Workspace or configuration changed; preview again"
        }
        val file = dao.path(workspaceId, foldedPath(template.target.path))
        require(file?.contentBlob == baseline) { "Capture target changed; preview again" }
        val source = file?.let { text(it) } ?: ""
        val doc = engine.parse(template.target.path, source, state.value.configuration)
        val result =
            CaptureEngine.insert(
                rendered,
                template,
                doc,
                date = date,
                createMissing = createMissing,
            )
        return if (file == null) create(template.target.path, result)
        else save(file.id, result, baseline)
    }

    suspend fun saveConfiguration(
        config: WorkspaceConfig,
        expectedBlob: String?,
        expectedWorkspaceId: String? = null,
    ): FileEntity {
        val source = ConfigurationCodec.encode(config)
        val snapshot = state.value
        val workspaceId = snapshot.workspace?.id ?: error("No workspace")
        require(expectedWorkspaceId == null || expectedWorkspaceId == workspaceId) {
            "Workspace changed; reopen the editor before saving"
        }
        val file = snapshot.files.firstOrNull { it.path == "config.json" && !it.trashed }
        return if (file == null) {
            require(expectedBlob == null)
            create("config.json", source, expectedWorkspaceId = workspaceId)
        } else save(file.id, source, expectedBlob)
    }

    suspend fun remember(
        fileId: String,
        readingByte: Int? = null,
        pin: Boolean? = null,
        opened: Boolean = false,
    ) {
        stateMutex.withLock {
            val file = dao.file(fileId) ?: return@withLock
            dao.put(
                file.copy(
                    readingByte = readingByte ?: file.readingByte,
                    pinned = pin ?: file.pinned,
                    openedAt = if (opened) System.currentTimeMillis() else file.openedAt,
                )
            )
        }
        if (pin != null || opened) publish()
    }

    suspend fun move(
        fileId: String,
        destination: String,
        kind: String = "move",
        undoGeneration: Long? = null,
        expected: List<FileEntity>? = null,
    ) {
        safePath(destination)
        stateMutex.withLock {
            database.withTransaction {
                val file = dao.file(fileId) ?: error("File missing")
                if (undoGeneration != null)
                    require(file.generation == undoGeneration) {
                        "File changed after this action; reopen it before moving"
                    }
                require(!destination.startsWith(file.path + "/")) {
                    "Cannot move a folder into itself"
                }
                val files = dao.files(file.workspaceId)
                val moving = files.filter {
                    it.id == file.id ||
                        file.directory &&
                            it.trashed == file.trashed &&
                            it.remotePath.startsWith(file.remotePath + "/")
                }
                if (expected != null) {
                    fun signature(values: List<FileEntity>) = values.associate {
                        it.id to
                            listOf(it.path, it.directory, it.trashed, it.contentBlob, it.generation)
                    }
                    require(signature(moving) == signature(expected)) {
                        "The file or folder changed. Reopen it before moving or deleting."
                    }
                }
                require(moving.none { it.conflict || it.dependencyId != null }) {
                    "Resolve conflicts before moving"
                }
                for (child in moving) {
                    val path = destination + child.path.removePrefix(file.path)
                    require(files.none { it !in moving && it.folded == foldedPath(path) }) {
                        "Destination already exists"
                    }
                }
                val physical =
                    if (kind == "trash")
                        ".orgenda-trash/${UUID.randomUUID().toString().uppercase()}/item"
                    else destination
                val queued = dao.operations(file.workspaceId)
                require(
                    queued.none {
                        it.fileId in moving.map { child -> child.id } &&
                            (undoGeneration == null || it.fileId != file.id)
                    }
                ) {
                    "Finish the pending file operation first"
                }
                val physicalSource =
                    queued.filter { it.fileId == file.id }.maxByOrNull { it.sequence }?.to
                        ?: file.remotePath
                val targets =
                    if (
                        dao.workspace(file.workspaceId)?.kind == "saf" &&
                            physicalSource.substringBeforeLast('/', "") !=
                                physical.substringBeforeLast('/', "") &&
                            physicalSource.substringAfterLast('/') !=
                                physical.substringAfterLast('/')
                    ) {
                        val temporary = ".orgenda-move-${UUID.randomUUID()}"
                        fun under(parent: String) =
                            if (parent.isEmpty()) temporary else "$parent/$temporary"
                        listOf(
                            under(physicalSource.substringBeforeLast('/', "")),
                            under(physical.substringBeforeLast('/', "")),
                            physical,
                        )
                    } else listOf(physical)
                var previous = physicalSource
                val sequence =
                    maxOf(System.currentTimeMillis(), (queued.maxOfOrNull { it.sequence } ?: 0) + 1)
                targets.forEachIndexed { index, target ->
                    dao.putOperation(
                        OperationEntity(
                            UUID.randomUUID().toString(),
                            file.workspaceId,
                            file.id,
                            kind,
                            previous,
                            target,
                            file.baseBlob,
                            file.revision,
                            file.directory,
                            sequence + index,
                        )
                    )
                    previous = target
                }
                moving.forEach { child ->
                    val path =
                        if (kind == "trash") child.path
                        else destination + child.path.removePrefix(file.path)
                    dao.put(
                        child.copy(
                            path = path,
                            folded =
                                if (kind == "trash") "trash:${child.id}:${foldedPath(path)}"
                                else foldedPath(path),
                            trashed = kind == "trash",
                            deletedAt = if (kind == "trash") System.currentTimeMillis() else 0,
                            generation = child.generation + 1,
                        )
                    )
                }
            }
        }
        publish()
    }

    suspend fun restore(file: FileEntity, destination: String = file.path) {
        move(file.id, destination, "restore", undoGeneration = file.generation)
    }

    suspend fun refile(
        item: OrgHeading,
        destinationPath: String,
        parentStart: Int? = null,
        archiveHeading: String? = null,
    ) {
        require(state.value.configurationError == null)
        safePath(destinationPath)
        val snapshot = state.value
        val ws = snapshot.workspace!!.id
        val source = snapshot.files.first { it.path == item.path && !it.trashed }
        val destination = snapshot.files.firstOrNull { it.path == destinationPath && !it.trashed }
        val sourceText = text(source)
        require(sourceText.sha256() == item.sourceHash)
        val a = engine.parse(source.path, sourceText, snapshot.configuration)
        var targetText = destination?.let { text(it) } ?: ""
        var b = engine.parse(destinationPath, targetText, snapshot.configuration)
        var parent = parentStart?.let { b.heading(it) }
        if (!archiveHeading.isNullOrBlank()) {
            val title = archiveHeading.trim().trimStart('*').trimStart()
            val matches = b.headings.filter { it.title == title }
            require(matches.size <= 1) { "Ambiguous archive heading" }
            parent = matches.firstOrNull()
            if (parent == null) {
                targetText +=
                    (if (targetText.isNotEmpty() && !targetText.endsWith('\n')) "\n" else "") +
                        archiveHeading.trim() +
                        "\n"
                b = engine.parse(destinationPath, targetText, snapshot.configuration)
                parent = b.headings.last()
            }
        }
        val (newSource, newDestination) = engine.moveSubtree(a, item, b, parent)
        val sourceBlob = blobs.put(newSource)
        val destinationBlob = blobs.put(newDestination)
        stateMutex.withLock {
            database.withTransaction {
                require(activeId == ws && dao.file(source.id)?.contentBlob == source.contentBlob)
                if (source.path == destinationPath)
                    dao.put(source.copy(draftBlob = sourceBlob, generation = source.generation + 1))
                else {
                    require(
                        destination?.let { dao.file(it.id)?.contentBlob == it.contentBlob }
                            ?: (dao.path(ws, foldedPath(destinationPath)) == null)
                    )
                    require(
                        !source.conflict &&
                            source.dependencyId == null &&
                            destination?.conflict != true &&
                            destination?.dependencyId == null
                    ) {
                        "Finish pending cross-file edits first"
                    }
                    val target =
                        (destination
                                ?: FileEntity(UUID.randomUUID().toString(), ws, destinationPath))
                            .copy(draftBlob = destinationBlob)
                    dao.put(target)
                    dao.put(
                        source.copy(
                            draftBlob = sourceBlob,
                            dependencyId = target.id,
                            dependencyBlob = destinationBlob,
                            generation = source.generation + 1,
                        )
                    )
                }
            }
        }
        publish()
    }

    suspend fun resolve(fileId: String, choice: String, expectedGeneration: Long) {
        stateMutex.withLock {
            database.withTransaction {
                val file = dao.file(fileId) ?: error("Missing conflict")
                require(file.conflict && file.generation == expectedGeneration) {
                    "Conflict changed; review the latest versions"
                }
                if (choice == "both") {
                    val path =
                        file.path.substringBeforeLast('.', file.path) +
                            " (local ${System.currentTimeMillis()})." +
                            file.path.substringAfterLast('.', "org")
                    val local = file.contentBlob ?: error("Missing local copy")
                    dao.put(
                        FileEntity(
                            UUID.randomUUID().toString(),
                            file.workspaceId,
                            path,
                            draftBlob = local,
                        )
                    )
                }
                if (choice == "local")
                    dao.put(
                        file.copy(
                            baseBlob = file.remoteBlob,
                            draftBlob = file.contentBlob,
                            conflict = false,
                            remoteBlob = null,
                            remoteMissing = false,
                            generation = file.generation + 1,
                        )
                    )
                else if (file.remoteMissing) dao.remove(file.id)
                else
                    dao.put(
                        file.copy(
                            baseBlob = file.remoteBlob,
                            draftBlob = null,
                            conflict = false,
                            remoteBlob = null,
                            remoteMissing = false,
                            dependencyId = null,
                            dependencyBlob = null,
                            generation = file.generation + 1,
                        )
                    )
            }
        }
        publish()
    }

    suspend fun exportRecovery(output: java.io.OutputStream) =
        withContext(Dispatchers.IO) {
            val manifests = dao.workspaces().associate { it.id to recoveryManifest(it.id) }
            java.util.zip.ZipOutputStream(output).use { zip ->
                for ((id, manifest) in manifests) {
                    zip.putNextEntry(java.util.zip.ZipEntry("$id/manifest.json"))
                    zip.write(Json.encodeToString(manifest).utf8Bytes())
                    zip.closeEntry()
                    for (file in manifest.files) {
                        val copies =
                            listOf(
                                "local" to file.contentBlob,
                                "remote" to file.remoteBlob,
                                "base" to file.baseBlob,
                            )
                        for ((kind, blob) in copies) if (blob != null) {
                            zip.putNextEntry(
                                java.util.zip.ZipEntry("$id/$kind/${file.id}/${file.path}")
                            )
                            zip.write(blobs.read(blob))
                            zip.closeEntry()
                        }
                    }
                }
            }
            check(
                preferences
                    .edit()
                    .also { editor ->
                        manifests.forEach { (id, manifest) ->
                            editor.putString("exported:$id", Json.encodeToString(manifest).sha256())
                        }
                    }
                    .commit()
            )
        }

    suspend fun attachment(path: String): ByteArray =
        withContext(Dispatchers.IO) {
            safePath(path)
            val workspace = state.value.workspace ?: error("No workspace")
            val cached = dao.attachment(workspace.id, path)
            try {
                val content = backend(workspace).read(path, 20 * 1024 * 1024)
                val hash = blobs.put(content.bytes)
                dao.putAttachment(
                    AttachmentEntity(workspace.id, path, hash, System.currentTimeMillis())
                )
                content.bytes
            } catch (e: Exception) {
                if (e is java.io.IOException || e is StorageFailure.Unavailable)
                    cached?.let { blobs.read(it.blob) } ?: throw e
                else throw e
            }
        }

    suspend fun sync(id: String? = activeId, pull: Boolean = true, manual: Boolean = false) =
        withContext(Dispatchers.IO) {
            val workspaceId = id ?: return@withContext
            syncMutex.withLock {
                val workspace = dao.workspace(workspaceId) ?: return@withLock
                if (System.currentTimeMillis() < workspace.retryAfter)
                    throw StorageFailure.Throttled(
                        (workspace.retryAfter - System.currentTimeMillis()) / 1000 + 1
                    )
                if (activeId == workspaceId)
                    mutableState.update { it.copy(syncing = true, error = null) }
                try {
                    val remote = backend(workspace)
                    if (
                        pull &&
                            (manual ||
                                System.currentTimeMillis() - workspace.lastSync >=
                                    (minimumPoll[workspaceId] ?: 30) * 1000)
                    ) {
                        pull(workspace, remote)
                        val latest = dao.workspace(workspaceId)!!
                        dao.putWorkspace(
                            latest.copy(lastSync = System.currentTimeMillis(), retryAfter = 0)
                        )
                    }
                    // Pull can merge drafts, and edits may arrive during an upload.
                    push(workspace, remote)
                    push(workspace, remote)
                    if (activeId == workspaceId) mutableState.update { it.copy(syncError = null) }
                } catch (e: Exception) {
                    if (e is StorageFailure.Throttled)
                        dao.putWorkspace(
                            dao.workspace(workspaceId)!!.copy(
                                retryAfter = System.currentTimeMillis() + e.retrySeconds * 1000
                            )
                        )
                    if (activeId == workspaceId) {
                        error(e)
                        mutableState.update { it.copy(syncError = e.message) }
                    }
                    throw e
                } finally {
                    if (activeId == workspaceId) {
                        mutableState.update { it.copy(syncing = false) }
                        publish()
                    }
                }
            }
        }

    private suspend fun push(workspace: WorkspaceEntity, remote: StorageBackend) {
        // Content is uploaded at the physical path before queued moves replay.
        val files = dao.files(workspace.id)
        for (snapshot in files.filter { it.draftBlob != null && !it.conflict && !it.directory }) {
            val draft = snapshot.draftBlob!!
            try {
                if (snapshot.dependencyId != null) {
                    val destination = dao.file(snapshot.dependencyId)
                    if (
                        destination == null ||
                            destination.baseBlob != snapshot.dependencyBlob ||
                            destination.draftBlob != null ||
                            destination.conflict
                    )
                        continue
                    val live = remote.read(destination.remotePath)
                    if (live.hash != snapshot.dependencyBlob) {
                        conflict(snapshot, remote)
                        continue
                    }
                }
                val bytes = blobs.read(draft)
                val expected =
                    snapshot.baseBlob?.let { hash ->
                        val revision =
                            snapshot.revision
                                ?: remote
                                    .read(snapshot.remotePath)
                                    .also {
                                        if (it.hash != hash)
                                            throw StorageFailure.Conflict(snapshot.remotePath)
                                    }
                                    .entry
                                    .revision
                        RemoteVersion(revision, hash)
                    }
                val result =
                    try {
                        remote.write(snapshot.remotePath, bytes, expected)
                    } catch (e: StorageFailure.Conflict) {
                        // A lost successful response can be acknowledged only by exact byte
                        // equality.
                        val live = remote.read(snapshot.remotePath)
                        if (live.hash == draft) live else throw e
                    }
                stateMutex.withLock {
                    val current = dao.file(snapshot.id) ?: return@withLock
                    dao.put(
                        current.copy(
                            baseBlob = draft,
                            revision = result.entry.revision,
                            remoteIdentity = result.entry.identity,
                            draftBlob = current.draftBlob.takeUnless { it == draft },
                            dependencyId =
                                if (current.draftBlob == draft) null else current.dependencyId,
                            dependencyBlob =
                                if (current.draftBlob == draft) null else current.dependencyBlob,
                        )
                    )
                }
            } catch (e: StorageFailure.Conflict) {
                conflict(snapshot, remote)
            } catch (e: StorageFailure.Missing) {
                conflict(snapshot, remote)
            } catch (e: Exception) {
                if (
                    e is StorageFailure.Authentication ||
                        e is StorageFailure.Throttled ||
                        e is java.io.IOException
                )
                    throw e
                if (activeId == workspace.id) error(e)
            }
        }
        for (operation in dao.operations(workspace.id)) {
            if (operation.error != null) continue
            val current = dao.file(operation.fileId) ?: continue
            if (
                dao.operations(workspace.id).any {
                    it.fileId == operation.fileId && it.sequence < operation.sequence
                }
            )
                continue
            val descendants =
                dao.files(workspace.id).filter {
                    it.id == current.id ||
                        current.directory && it.remotePath.startsWith(current.remotePath + "/")
                }
            if (descendants.any { it.draftBlob != null || it.conflict }) continue
            try {
                if (operation.kind == "trash") {
                    val final =
                        dao.operations(workspace.id)
                            .filter { it.fileId == current.id && it.kind == "trash" }
                            .maxBy { it.sequence }
                            .to
                    val folder = final.substringBeforeLast('/')
                    if (folder.startsWith(".orgenda-trash/")) {
                        val metadata =
                            TrashMetadata(
                                folder.substringAfterLast('/'),
                                current.path,
                                current.path.substringAfterLast('/').substringBeforeLast('.'),
                                if (current.directory) "folder"
                                else if (current.path.endsWith(".md")) "markdown" else "org",
                                (current.deletedAt.takeIf { it > 0 } ?: operation.sequence) /
                                    1000.0 - 978307200.0,
                            )
                        val bytes = Json.encodeToString(metadata).toByteArray()
                        val path = "$folder/info.json"
                        val old =
                            try {
                                remote.read(path, 1024 * 1024)
                            } catch (_: StorageFailure.Missing) {
                                null
                            }
                        if (old == null) remote.write(path, bytes, null)
                        else {
                            val existing =
                                Json.decodeFromString<TrashMetadata>(old.bytes.strictText())
                            require(
                                existing.id == metadata.id &&
                                    existing.originalPath == metadata.originalPath &&
                                    existing.kind == metadata.kind
                            ) {
                                "Trash metadata changed; recovery copy retained"
                            }
                        }
                    }
                }
                if (operation.kind == "mkdir") remote.mkdir(operation.to)
                else {
                    // Directory moves require a fresh, complete inventory and byte checks for every
                    // cached child.
                    var alreadyMoved = false
                    if (current.directory) {
                        val inventory = remote.scan()
                        require(
                            inventory.unavailable.keys.none { path ->
                                listOf(current.remotePath, operation.to).any {
                                    path == it ||
                                        path.startsWith(it + "/") ||
                                        it.startsWith(path + "/")
                                }
                            }
                        )
                        val sourceExists =
                            inventory.entries.any {
                                it.path == current.remotePath ||
                                    it.path.startsWith(current.remotePath + "/")
                            }
                        val destinationExists =
                            inventory.entries.any { it.directory && it.path == operation.to }
                        alreadyMoved = !sourceExists && destinationExists
                        val root = if (alreadyMoved) operation.to else current.remotePath
                        val known =
                            descendants
                                .filter { !it.directory }
                                .map { root + it.remotePath.removePrefix(current.remotePath) }
                                .toSet()
                        val live =
                            inventory.entries
                                .filter {
                                    !it.directory &&
                                        it.path.startsWith(root + "/") &&
                                        isWorkspaceText(it.path)
                                }
                                .map { it.path }
                                .toSet()
                        require(known == live) { "Folder contents changed; refresh before moving" }
                        if (alreadyMoved)
                            require(
                                descendants
                                    .filter { it.directory }
                                    .all { child ->
                                        inventory.entries.any {
                                            it.directory &&
                                                it.path ==
                                                    root +
                                                        child.remotePath.removePrefix(
                                                            current.remotePath
                                                        )
                                        }
                                    }
                            ) {
                                "Moved folder is incomplete"
                            }
                        for (child in descendants.filter { !it.directory }) if (
                            remote
                                .read(root + child.remotePath.removePrefix(current.remotePath))
                                .hash != child.baseBlob
                        )
                            throw StorageFailure.Conflict(child.remotePath)
                    }
                    try {
                        if (!alreadyMoved)
                            remote.move(
                                current.remotePath,
                                operation.to,
                                current.baseBlob?.let { RemoteVersion(current.revision, it) },
                                current.directory,
                            )
                    } catch (e: StorageFailure.Missing) {
                        if (current.directory || remote.read(operation.to).hash != current.baseBlob)
                            throw e
                    }
                }
                val movedContent = if (!current.directory) remote.read(operation.to) else null
                if (movedContent != null && movedContent.hash != current.baseBlob)
                    throw StorageFailure.Conflict(operation.to)
                stateMutex.withLock {
                    database.withTransaction {
                        val latest = dao.file(current.id) ?: return@withTransaction
                        val matching =
                            dao.files(workspace.id).filter {
                                it.id == latest.id ||
                                    latest.directory &&
                                        it.remotePath.startsWith(current.remotePath + "/")
                            }
                        for (child in matching) dao.put(
                            child.copy(
                                remotePath =
                                    operation.to +
                                        child.remotePath.removePrefix(current.remotePath),
                                revision =
                                    if (child.id == current.id) movedContent?.entry?.revision
                                    else null,
                                remoteIdentity =
                                    if (child.id == current.id)
                                        movedContent?.entry?.identity ?: child.remoteIdentity
                                    else child.remoteIdentity,
                            )
                        )
                        dao.removeOperation(operation.id)
                    }
                }
            } catch (e: Exception) {
                if (
                    e is StorageFailure.Authentication ||
                        e is StorageFailure.Throttled ||
                        e is java.io.IOException
                )
                    throw e
                dao.putOperation(operation.copy(error = e.message ?: "Operation failed"))
            }
        }
    }

    private suspend fun conflict(snapshot: FileEntity, remote: StorageBackend) {
        val live =
            try {
                remote.read(snapshot.remotePath)
            } catch (e: StorageFailure.Missing) {
                null
            }
        val hash = live?.let { blobs.put(it.bytes) }
        stateMutex.withLock {
            val current = dao.file(snapshot.id) ?: return@withLock
            val merged =
                if (
                    live != null &&
                        current.baseBlob != null &&
                        current.draftBlob != null &&
                        current.dependencyId == null
                )
                    TextMerge.merge(
                        blobs.text(current.baseBlob),
                        blobs.text(current.draftBlob),
                        live.bytes.strictText(),
                    )
                else null
            if (merged != null)
                dao.put(
                    current.copy(
                        baseBlob = hash,
                        draftBlob = blobs.put(merged).takeUnless { it == hash },
                        conflict = false,
                        remoteBlob = null,
                        remoteMissing = false,
                        revision = live?.entry?.revision,
                        generation = current.generation + 1,
                    )
                )
            else
                dao.put(
                    current.copy(
                        conflict = true,
                        remoteBlob = hash,
                        remoteMissing = live == null,
                        revision = live?.entry?.revision,
                        generation = current.generation + 1,
                    )
                )
        }
    }

    private suspend fun importTrash(
        workspace: WorkspaceEntity,
        remote: StorageBackend,
        inventory: RemoteInventory,
    ) {
        val json = Json { ignoreUnknownKeys = true }
        for (info in
            inventory.entries.filter {
                it.path.matches(Regex("\\.orgenda-trash/[^/]+/info\\.json"))
            }) {
            try {
                val metadata =
                    json.decodeFromString<TrashMetadata>(
                        remote.read(info.path, 1024 * 1024).bytes.strictText()
                    )
                require(
                    UUID.fromString(metadata.id).toString().equals(info.path.split('/')[1], true)
                )
                safePath(metadata.originalPath)
                require(metadata.originalPath.split('/').none { it.startsWith('.') })
                val item = info.path.substringBeforeLast('/') + "/item"
                val root = inventory.entries.firstOrNull { it.path == item } ?: continue
                val entries =
                    inventory.entries.filter {
                        it.path == item ||
                            root.directory &&
                                it.path.startsWith("$item/") &&
                                (it.directory || isWorkspaceText(it.path))
                    }
                for (entry in entries) {
                    val path = metadata.originalPath + entry.path.removePrefix(item)
                    val body = if (entry.directory) null else remote.read(entry.path)
                    val hash = body?.let {
                        it.bytes.strictText()
                        blobs.put(it.bytes)
                    }
                    stateMutex.withLock {
                        if (dao.operations(workspace.id).any { it.to == item || it.from == item })
                            return@withLock
                        val existing =
                            dao.files(workspace.id).firstOrNull { it.remotePath == entry.path }
                        if (existing?.draftBlob != null) return@withLock
                        val id = existing?.id ?: UUID.randomUUID().toString()
                        dao.put(
                            (existing ?: FileEntity(id, workspace.id, path)).copy(
                                folded = "trash:$id:${foldedPath(path)}",
                                remotePath = entry.path,
                                remoteIdentity = entry.identity,
                                directory = entry.directory,
                                baseBlob = hash,
                                revision = body?.entry?.revision,
                                trashed = true,
                                deletedAt = ((metadata.deletedAt + 978307200) * 1000).toLong(),
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is StorageFailure.Authentication || e is StorageFailure.Throttled) throw e
                if (activeId == workspace.id) error(e)
            }
        }
    }

    private suspend fun pull(workspace: WorkspaceEntity, remote: StorageBackend) {
        val inventory = remote.scan()
        minimumPoll[workspace.id] = inventory.minimumPollSeconds
        val queuedMoves = dao.operations(workspace.id)
        // SAF document IDs can survive external renames. Preserve local drafts and reading state.
        if (workspace.kind == "saf")
            stateMutex.withLock {
                val files = dao.files(workspace.id)
                val operations = dao.operations(workspace.id)
                for (entry in inventory.entries.filter { it.identity != it.path }) {
                    val candidates = files.filter { it.remoteIdentity == entry.identity }
                    val file = candidates.singleOrNull() ?: continue
                    if (
                        file.remotePath != entry.path &&
                            operations.none { it.fileId == file.id } &&
                            dao.path(workspace.id, foldedPath(entry.path)) == null
                    ) {
                        dao.put(
                            file.copy(
                                path = entry.path,
                                folded = foldedPath(entry.path),
                                remotePath = entry.path,
                                revision = null,
                                generation = file.generation + 1,
                            )
                        )
                    }
                }
            }
        val seen = mutableSetOf<String>()
        for (entry in
            inventory.entries.filter {
                (it.directory || isWorkspaceText(it.path)) && !isRecoveryPath(it.path)
            }) {
            seen += entry.path
            if (
                queuedMoves.any {
                    it.kind != "mkdir" &&
                        (entry.path == it.to || it.directory && entry.path.startsWith(it.to + "/"))
                }
            )
                continue
            val before = dao.files(workspace.id).firstOrNull { it.remotePath == entry.path }
            if (before?.trashed == true) continue
            if (entry.directory) {
                if (before == null)
                    stateMutex.withLock {
                        if (dao.path(workspace.id, foldedPath(entry.path)) == null)
                            dao.put(
                                FileEntity(
                                    UUID.randomUUID().toString(),
                                    workspace.id,
                                    entry.path,
                                    directory = true,
                                    remoteIdentity = entry.identity,
                                )
                            )
                    }
                continue
            }
            // Do not trust mtime/size or weak/missing ETags to skip a read.
            if (
                before != null &&
                    entry.revision?.startsWith("W/") == false &&
                    before.revision == entry.revision &&
                    before.baseBlob != null &&
                    !before.conflict
            )
                continue
            val live =
                try {
                    remote.read(entry.path)
                } catch (e: Exception) {
                    if (e is StorageFailure.Authentication || e is StorageFailure.Throttled) throw e
                    if (activeId == workspace.id) error(e)
                    continue
                }
            live.bytes.strictText()
            val hash = blobs.put(live.bytes)
            stateMutex.withLock {
                database.withTransaction {
                    var current =
                        dao.files(workspace.id).firstOrNull { it.remotePath == entry.path }
                    if (current == null) {
                        current = dao.path(workspace.id, foldedPath(entry.path))
                        if (current != null) {
                            dao.put(
                                current.copy(
                                    conflict = true,
                                    remoteBlob = hash,
                                    revision = live.entry.revision,
                                    generation = current.generation + 1,
                                )
                            )
                            return@withTransaction
                        }
                        dao.put(
                            FileEntity(
                                UUID.randomUUID().toString(),
                                workspace.id,
                                entry.path,
                                remoteIdentity = live.entry.identity,
                                revision = live.entry.revision,
                                baseBlob = hash,
                            )
                        )
                    } else if (current.draftBlob != null && current.baseBlob != hash) {
                        val merged =
                            if (current.baseBlob != null && current.dependencyId == null)
                                TextMerge.merge(
                                    blobs.text(current.baseBlob),
                                    blobs.text(current.draftBlob),
                                    live.bytes.strictText(),
                                )
                            else null
                        if (merged != null)
                            dao.put(
                                current.copy(
                                    baseBlob = hash,
                                    draftBlob = blobs.put(merged).takeUnless { it == hash },
                                    conflict = false,
                                    remoteBlob = null,
                                    remoteMissing = false,
                                    revision = live.entry.revision,
                                    generation = current.generation + 1,
                                )
                            )
                        else
                            dao.put(
                                current.copy(
                                    conflict = true,
                                    remoteBlob = hash,
                                    remoteMissing = false,
                                    revision = live.entry.revision,
                                    generation = current.generation + 1,
                                )
                            )
                    } else if (!current.conflict)
                        dao.put(
                            current.copy(
                                baseBlob = hash,
                                revision = live.entry.revision,
                                remoteIdentity = live.entry.identity,
                                generation =
                                    if (current.baseBlob == hash) current.generation
                                    else current.generation + 1,
                            )
                        )
                }
            }
        }
        stateMutex.withLock {
            val ops = dao.operations(workspace.id)
            for (file in dao.files(workspace.id)) {
                if (
                    file.trashed ||
                        file.remotePath in seen ||
                        file.baseBlob == null && file.remoteIdentity == null ||
                        ops.any {
                            it.fileId == file.id || file.remotePath.startsWith(it.from + "/")
                        }
                )
                    continue
                if (
                    inventory.unavailable.keys.any {
                        file.remotePath == it || file.remotePath.startsWith(it + "/")
                    }
                )
                    continue
                if (file.draftBlob != null || file.conflict)
                    dao.put(
                        file.copy(
                            conflict = true,
                            remoteMissing = true,
                            remoteBlob = null,
                            generation = file.generation + 1,
                        )
                    )
                else dao.remove(file.id)
            }
        }
        importTrash(workspace, remote, inventory)
    }

    suspend fun retryOperation(id: String) {
        dao.operations(activeId!!)
            .firstOrNull { it.id == id }
            ?.let { dao.putOperation(it.copy(error = null)) }
        publish()
    }
}
