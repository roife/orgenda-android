package com.roifewu.orgenda

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.*
import com.roifewu.orgenda.platform.SyncWorker
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EditorState(
    val file: FileEntity,
    val text: String,
    val baseline: String?,
    val document: OrgDocument?,
    val preview: Boolean = true,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val jumpByte: Int = 0,
    val readingByte: Int = 0,
    val session: Long = System.nanoTime(),
    val searchQuery: String = "",
    val navigationRevision: Long = 0,
    val headingMove: HeadingMove? = null,
    val headingMoveKeys: Map<String, String> = emptyMap(),
    val focusRevision: Long = 0,
)

data class FileGestureUndo(
    val before: FileEntity,
    val generation: Long,
    val id: Long = System.nanoTime(),
)

data class GestureUndo(
    val fileId: String,
    val before: String,
    val expected: String?,
    val id: Long = System.nanoTime(),
)

class OrgendaViewModel(application: Application) : AndroidViewModel(application) {
    val collapsedHeadings = mutableMapOf<String, Set<String>>()
    val editorHistories = mutableMapOf<Long, com.roifewu.orgenda.ui.EditorHistory>()
    val app = application as OrgendaApplication
    val repository
        get() = app.repository

    val workspace = repository.state
    val settings = app.settings.state
    private val mutableUndo = MutableStateFlow<GestureUndo?>(null)
    val gestureUndo = mutableUndo.asStateFlow()
    private val mutableFileUndo = MutableStateFlow<FileGestureUndo?>(null)
    val fileUndo = mutableFileUndo.asStateFlow()
    private val mutableEditor = MutableStateFlow<EditorState?>(null)
    val editor = mutableEditor.asStateFlow()
    private var autosave: Job? = null
    private val openGeneration = java.util.concurrent.atomic.AtomicLong()
    private val editorSave = Mutex()
    private val configurationEditor =
        ConfigurationEditor(
            workspace,
            repository::saveConfiguration,
            viewModelScope,
            app.getSharedPreferences("configuration-drafts", android.content.Context.MODE_PRIVATE),
        ) { workspaceId -> SyncWorker.enqueue(app, workspaceId) }
    val configuration = configurationEditor.state

    init {
        viewModelScope.launch {
            app.ready.await()
            workspace.collect { snapshot ->
                configurationEditor.accept(snapshot)
                val current = mutableEditor.value ?: return@collect
                val file = snapshot.files.firstOrNull { it.id == current.file.id } ?: return@collect
                if (
                    !current.dirty &&
                        !current.saving &&
                        (current.baseline != file.contentBlob ||
                            current.file.path != file.path ||
                            current.file.generation != file.generation)
                ) {
                    val text = withContext(Dispatchers.IO) { repository.text(file) }
                    if (
                        mutableEditor.value?.session == current.session &&
                            mutableEditor.value?.dirty == false
                    ) {
                        val parsed = parseEditor(file, text)
                        val localSources =
                            localChangeSources(current.file, file, snapshot.localDocumentEdits)
                        withContext(Dispatchers.Main.immediate) {
                            val latest = mutableEditor.value
                            if (
                                latest?.session == current.session &&
                                    !latest.dirty &&
                                    !latest.saving &&
                                    latest.baseline == current.baseline &&
                                    latest.text == current.text
                            ) {
                                acceptBackingSource(current, text, localSources)
                                mutableEditor.value =
                                    latest.copy(
                                        file = file,
                                        text = text,
                                        baseline = file.contentBlob,
                                        document = parsed,
                                    )
                            }
                        }
                    }
                } else
                    mutableEditor.update { latest ->
                        if (
                            latest?.session == current.session &&
                                latest.baseline == file.contentBlob
                        )
                            latest.copy(file = file)
                        else latest
                    }
            }
        }
    }

    fun action(onFailure: (Exception) -> Unit = {}, block: suspend () -> Unit) =
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                SyncWorker.enqueue(app, workspace.value.workspace?.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repository.error(e)
                withContext(Dispatchers.Main.immediate) { onFailure(e) }
            }
        }

    fun sync() = action { repository.sync(pull = true, manual = true) }

    fun configure(value: WorkspaceConfig) = configurationEditor.configure(value)

    fun configureRaw(raw: String) = configurationEditor.configureRaw(raw)

    suspend fun saveConfigurationNow() = configurationEditor.saveNow()

    suspend fun saveConfigurationEdit(
        value: WorkspaceConfig,
        expected: WorkspaceConfig,
        expectedWorkspaceId: String,
    ) = configurationEditor.saveEdit(value, expected, expectedWorkspaceId)

    fun discardConfiguration() = configurationEditor.discard()

    private suspend fun parseEditor(file: FileEntity, text: String): OrgDocument? =
        withContext(Dispatchers.Default) {
            if (file.path.endsWith(".org") || file.path.endsWith(".org_archive"))
                repository.engine.parse(file.path, text, workspace.value.configuration)
            else null
        }

    private suspend fun localChangeSources(
        before: FileEntity,
        after: FileEntity,
        changes: List<LocalDocumentEdit>,
    ): List<String>? =
        withContext(Dispatchers.IO) {
            var current = before
            val sources = mutableListOf<String>()
            var length = 0L
            while (current.generation < after.generation) {
                val change =
                    changes.firstOrNull {
                        it.before.id == current.id &&
                            it.before.generation == current.generation &&
                            it.before.contentBlob == current.contentBlob
                    } ?: return@withContext null
                current = change.after
                val source = repository.text(current)
                length += source.length
                if (length > 8 * 1024 * 1024) return@withContext null
                sources += source
            }
            sources.takeIf {
                current.generation == after.generation && current.contentBlob == after.contentBlob
            }
        }

    // Called on Main so native typing and backing-source changes cannot race the
    // same undo stacks. Unknown/external versions keep the existing reset policy.
    private fun acceptBackingSource(
        editor: EditorState,
        text: String,
        localSources: List<String>?,
    ) {
        val history =
            editorHistories.getOrPut(editor.session) {
                com.roifewu.orgenda.ui.EditorHistory().apply { acceptSource(editor.text) }
            }
        if (localSources == null) {
            if (editor.text != text) history.acceptSource(text)
        } else {
            var previous = editor.text
            localSources.forEach { next ->
                history.acceptLocalChange(previous, next)
                previous = next
            }
        }
    }

    fun open(file: FileEntity, jumpByte: Int? = null, searchQuery: String = ""): Job {
        val generation = openGeneration.incrementAndGet()
        return action {
            saveNow()
            val source = repository.text(file)
            val parsed = parseEditor(file, source)
            if (generation != openGeneration.get()) return@action
            mutableEditor.update { latest ->
                if (latest?.dirty == true || latest?.saving == true) latest
                else
                    EditorState(
                        file,
                        source,
                        file.contentBlob,
                        parsed,
                        jumpByte = jumpByte ?: file.readingByte,
                        readingByte = jumpByte ?: file.readingByte,
                        searchQuery = searchQuery,
                    )
            }
            if (mutableEditor.value?.file?.id == file.id)
                repository.remember(file.id, opened = true)
        }
    }

    fun edit(text: String, readingByte: Int) {
        val old = mutableEditor.value ?: return
        if (text == old.text) {
            mutableEditor.update { it?.copy(readingByte = readingByte) }
            return
        }
        mutableEditor.value =
            old.copy(text = text, dirty = true, error = null, readingByte = readingByte)
        autosave?.cancel()
        autosave = viewModelScope.launch {
            delay(350)
            try {
                withContext(NonCancellable) { saveNow() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableEditor.update {
                    if (it?.session == old.session) it.copy(saving = false, error = e.message)
                    else it
                }
            }
        }
    }

    suspend fun saveNow() = editorSave.withLock {
        val current = mutableEditor.value ?: return@withLock
        if (!current.dirty) return@withLock
        mutableEditor.update { if (it?.session == current.session) it.copy(saving = true) else it }
        try {
            val saved = repository.save(current.file.id, current.text, current.baseline)
            val parsed = parseEditor(saved, current.text)
            mutableEditor.update { latest ->
                if (latest?.session != current.session) latest
                else
                    latest.copy(
                        file = saved,
                        baseline = saved.contentBlob,
                        dirty = latest.text != current.text,
                        document = if (latest.text == current.text) parsed else latest.document,
                        saving = false,
                        error = null,
                    )
            }
            SyncWorker.enqueue(app, saved.workspaceId)
        } catch (e: Exception) {
            mutableEditor.update {
                if (it?.session == current.session) it.copy(saving = false, error = e.message)
                else it
            }
            throw e
        }
    }

    fun flush() {
        viewModelScope.launch { runCatching { saveNow() } }
    }

    fun closeEditor() = action {
        saveNow()
        mutableEditor.value?.let { repository.remember(it.file.id, it.readingByte) }
        mutableEditor.update { latest ->
            if (latest?.dirty == true || latest?.saving == true) latest
            else {
                latest?.let { editorHistories.remove(it.session) }
                null
            }
        }
    }

    fun discardEditor() {
        autosave?.cancel()
        mutableEditor.value = null
    }

    fun preview(value: Boolean) = action {
        saveNow()
        val editor = mutableEditor.value ?: return@action
        val parsed = parseEditor(editor.file, editor.text)
        mutableEditor.update { latest ->
            if (latest?.session == editor.session && !latest.dirty && latest.text == editor.text)
                latest.copy(
                    preview = value,
                    document = parsed,
                    jumpByte = latest.readingByte,
                    navigationRevision = latest.navigationRevision + 1,
                )
            else latest
        }
    }

    fun reading(byte: Int) {
        mutableEditor.update { it?.copy(readingByte = byte) }
    }

    fun jump(byte: Int, focusEditor: Boolean = false) {
        mutableEditor.update {
            it?.copy(
                jumpByte = byte,
                readingByte = byte,
                navigationRevision = it.navigationRevision + 1,
                focusRevision = it.focusRevision + if (focusEditor && !it.preview) 1 else 0,
            )
        }
    }

    fun openOrgLink(origin: String, target: String) = action {
        val link = OrgLink.parse(origin, target)
        val snapshot = workspace.value
        val file =
            snapshot.files.firstOrNull { it.path == link.path && !it.trashed }
                ?: error("Linked file not found: ${link.path}")
        val document = snapshot.documents[link.path]
        val text =
            if (link.search?.toIntOrNull() != null) repository.text(file)
            else document?.text.orEmpty()
        val byte = link.byteOffset(document, text)
        if (link.search != null) require(byte != null) { "Link target not found: ${link.search}" }
        open(file, byte)
    }

    fun moveHeading(
        item: OrgHeading,
        target: OrgHeading,
        placement: HeadingPlacement,
        onFailure: (Exception) -> Unit = {},
    ) = action(onFailure) {
        saveNow()
        val file = workspace.value.files.first { it.path == item.path }
        val document =
            repository.engine.parse(item.path, repository.text(file), workspace.value.configuration)
        val moved = Outline.move(document, item, target, placement)
        val keys = Outline.keys(document)
        val hidden = collapsedHeadings[item.path].orEmpty()
        val parsed = repository.engine.parse(item.path, moved.text, workspace.value.configuration)
        val updatedKeys = Outline.keys(parsed)
        val movedKeys =
            keys
                .mapNotNull { (start, key) ->
                    moved.starts[start]?.let(updatedKeys::get)?.let { key to it }
                }
                .toMap()
        val saved = repository.save(file.id, moved.text, file.contentBlob)
        collapsedHeadings[item.path] =
            document.headings
                .filter { keys[it.start] in hidden }
                .mapNotNull { moved.starts[it.start]?.let(updatedKeys::get) }
                .toSet()
        withContext(Dispatchers.Main.immediate) {
            val current = mutableEditor.value
            if (
                current?.file?.id == saved.id &&
                    !current.dirty &&
                    !current.saving &&
                    (current.text == document.text || current.text == moved.text)
            ) {
                if (current.text == document.text)
                    acceptBackingSource(current, moved.text, listOf(moved.text))
                mutableEditor.value =
                    current.copy(
                        file = saved,
                        text = moved.text,
                        baseline = saved.contentBlob,
                        document = parsed,
                        jumpByte = moved.starts[item.start] ?: item.start,
                        readingByte = moved.starts[item.start] ?: item.start,
                        navigationRevision = current.navigationRevision + 1,
                        headingMove = moved,
                        headingMoveKeys = movedKeys,
                    )
            }
        }
    }

    fun toggle(item: OrgHeading, note: String = "") = action {
        gesture(item) { doc, heading ->
            repository.engine.complete(doc, heading, workspace.value.configuration, note = note)
        }
    }

    fun reschedule(item: OrgHeading, day: LocalDate, note: String = "") = action {
        gesture(item) { document, heading ->
            require(
                heading.isTask &&
                    heading.kind != "event" &&
                    !(heading.scheduled == null && heading.deadline?.repeater != null)
            ) {
                "Open the item editor to change this item's dates and repeat rule together"
            }
            val existing = heading.scheduled
            repository.engine.setPlanning(
                document,
                heading,
                "SCHEDULED",
                day.atTime(existing?.date?.toLocalTime() ?: LocalTime.MIDNIGHT),
                existing?.hasTime ?: false,
                existing?.repeater?.token ?: "",
                workspace.value.configuration,
                note,
            )
        }
    }

    private suspend fun gesture(item: OrgHeading, change: (OrgDocument, OrgHeading) -> String) {
        var before = ""
        val saved =
            repository.mutate(item) { doc, heading ->
                before = doc.text
                change(doc, heading)
            }
        mutableUndo.value = GestureUndo(saved.id, before, saved.contentBlob)
    }

    fun undoGesture(change: GestureUndo) = action {
        repository.save(change.fileId, change.before, change.expected)
        clearGesture(change)
    }

    fun clearGesture(change: GestureUndo) {
        mutableUndo.update { if (it?.id == change.id) null else it }
    }

    fun moveFile(
        file: FileEntity,
        destination: String,
        trash: Boolean = false,
        expected: List<FileEntity> =
            workspace.value.files.filter {
                it.id == file.id ||
                    file.directory && !it.trashed && it.path.startsWith(file.path + "/")
            },
    ) = action { moveFileNow(file, destination, trash, expected) }

    suspend fun moveFileNow(
        file: FileEntity,
        destination: String,
        trash: Boolean = false,
        expected: List<FileEntity> =
            workspace.value.files.filter {
                it.id == file.id ||
                    file.directory && !it.trashed && it.path.startsWith(file.path + "/")
            },
    ) {
        require(
            workspace.value.workspace?.id == file.workspaceId &&
                workspace.value.files.firstOrNull { it.id == file.id }?.generation ==
                    file.generation
        ) {
            "File changed; reopen it before moving"
        }
        saveNow()
        repository.move(file.id, destination, if (trash) "trash" else "move", expected = expected)
        workspace.value.files
            .firstOrNull { it.id == file.id }
            ?.let { mutableFileUndo.value = FileGestureUndo(file, it.generation) }
        SyncWorker.enqueue(app, file.workspaceId)
    }

    fun undoFile(change: FileGestureUndo) = action {
        require(workspace.value.workspace?.id == change.before.workspaceId)
        repository.move(change.before.id, change.before.path, "restore", change.generation)
        clearFileUndo(change)
    }

    fun clearFileUndo(change: FileGestureUndo) {
        mutableFileUndo.update { if (it?.id == change.id) null else it }
    }

    fun archive(item: OrgHeading) = action { archiveNow(item) }

    suspend fun archiveNow(item: OrgHeading) {
        val snapshot = workspace.value
        val document = snapshot.documents[item.path] ?: error("Document changed")
        val target = ArchiveTarget.resolve(document, item, snapshot.configuration.files.archive)
        repository.refile(item, target.path, archiveHeading = target.heading)
    }

    suspend fun newJournal(day: LocalDate, title: String, body: String) {
        val snapshot = workspace.value
        val path =
            java.nio.file.Paths.get(snapshot.configuration.files.journal)
                .resolve("${day.year}.org")
                .normalize()
                .toString()
        val file = snapshot.files.firstOrNull { it.path == path && !it.trashed }
        val source = file?.let { repository.text(it) } ?: ""
        val doc = repository.engine.parse(path, source, snapshot.configuration)
        val updated = JournalIndex.append(doc, day.atTime(LocalTime.now()), title, body)
        if (file == null) repository.create(path, updated)
        else repository.save(file.id, updated, file.contentBlob)
        SyncWorker.enqueue(app, snapshot.workspace?.id)
    }
}
