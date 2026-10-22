package com.roifewu.orgenda

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageSafetyTest {
    private lateinit var context: Context
    private lateinit var repository: WorkspaceRepository
    private lateinit var remote: MemoryBackend

    @Before
    fun setup() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val id = "test-${UUID.randomUUID()}"
        context =
            object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this

                override fun getFilesDir() = File(base.cacheDir, id).apply { mkdirs() }

                override fun getDatabasePath(name: String) = File(filesDir, name)

                override fun getSharedPreferences(name: String, mode: Int) =
                    base.getSharedPreferences("$id-$name", mode)
            }
        remote = MemoryBackend()
        remote.files["a.org"] = "* TODO 中文😀\nfirst\nsecond\n"
        repository = WorkspaceRepository(context, backendOverride = { remote })
        repository.start()
    }

    @After
    fun cleanup() {
        repository.database.close()
        context.filesDir.deleteRecursively()
    }

    private fun file(path: String = "a.org") =
        repository.state.value.files.first { it.path == path }

    private suspend fun current(path: String = "a.org") = repository.text(file(path))

    @Test
    fun offlineDraftSurvivesDatabaseReopen() = runBlocking {
        val file = file()
        repository.save(file.id, "* TODO 離線😀\r\n", file.contentBlob)
        remote.offline = true
        repository.database.close()
        repository = WorkspaceRepository(context, backendOverride = { remote })
        repository.start()
        assertEquals("* TODO 離線😀\r\n", current())
        assertNotNull(file().draftBlob)
        val db = repository.database.openHelper.readableDatabase
        db.query("PRAGMA synchronous").use {
            it.moveToFirst()
            assertEquals(2, it.getInt(0))
        }
        remote.offline = false
        repository.sync(manual = true)
        assertEquals(current(), remote.files["a.org"])
        assertNull(file().draftBlob)
    }

    @Test
    fun undoMoveAndTrashBeforeSyncSurvivesRestart() = runBlocking {
        val original = file()
        repository.move(original.id, "renamed.org")
        val moved = file("renamed.org")
        repository.move(moved.id, original.path, "restore", moved.generation)
        repository.sync(pull = false)
        assertEquals(original.path, file().remotePath)
        assertFalse(remote.files.containsKey("renamed.org"))
        repository.move(original.id, original.path, "trash")
        val trashed = file()
        repository.move(trashed.id, original.path, "restore", trashed.generation)
        repository.database.close()
        repository = WorkspaceRepository(context, backendOverride = { remote })
        repository.start()
        repository.sync(manual = true)
        assertEquals("* TODO 中文😀\nfirst\nsecond\n", remote.files["a.org"])
        assertFalse(file().trashed)
        assertTrue(repository.state.value.operations.isEmpty())
    }

    @Test
    fun undoAfterLostTrashResponseKeepsMetadataAndRestores() = runBlocking {
        val original = file()
        repository.move(original.id, original.path, "trash")
        remote.loseMoveResponse = true
        runCatching { repository.sync(pull = false) }
        val trashed = file()
        repository.move(trashed.id, original.path, "restore", trashed.generation)
        repository.sync(manual = true)
        assertTrue(repository.state.value.operations.isEmpty())
        assertEquals(original.path, file().remotePath)
        assertFalse(file().trashed)
        assertTrue(remote.files["a.org"]!!.contains("中文😀"))
    }

    @Test
    fun folderReauthorizationKeepsTheSameOfflineWorkspace() = runBlocking {
        val uri = android.net.Uri.parse("content://test/tree/workspace")
        repository.connectFolder(uri, "QA")
        val id = repository.state.value.workspace!!.id
        val original = file()
        repository.save(original.id, "offline draft", original.contentBlob)
        repository.connectFolder(uri, "QA")
        assertEquals(id, repository.state.value.workspace!!.id)
        assertEquals(1, repository.workspaces().count { it.root == uri.toString() })
        assertEquals("offline draft", current())
    }

    @Test
    fun fileActionRejectsAnOutdatedSnapshot() = runBlocking {
        val original = file()
        repository.save(original.id, "newer", original.contentBlob)
        assertTrue(
            runCatching { repository.move(original.id, "moved.org", expected = listOf(original)) }
                .isFailure
        )
        assertEquals("newer", current())
        assertTrue(repository.state.value.operations.isEmpty())
    }

    @Test
    fun cachedAttachmentDoesNotHideDeletionOrBadContent() = runBlocking {
        remote.files["image.png"] = "data"
        assertEquals("data", repository.attachment("image.png").strictText())
        remote.offline = true
        assertEquals("data", repository.attachment("image.png").strictText())
        remote.offline = false
        remote.files.remove("image.png")
        assertTrue(
            runCatching { repository.attachment("image.png") }.exceptionOrNull()
                is StorageFailure.Missing
        )
    }

    @Test
    fun staleEditorCannotOverwriteAnotherCommittedEdit() = runBlocking {
        val original = file()
        repository.save(original.id, "* TODO newer\n", original.contentBlob)
        assertTrue(
            runCatching { repository.save(original.id, "* TODO stale\n", original.contentBlob) }
                .isFailure
        )
        assertEquals("* TODO newer\n", current())
    }

    @Test
    fun remoteCreateCollisionKeepsBothVersions() = runBlocking {
        val local = repository.create("new.org", "* TODO local\n")
        remote.files["new.org"] = "* TODO remote\n"
        repository.sync(pull = false)
        assertEquals("* TODO remote\n", remote.files["new.org"])
        assertEquals("* TODO local\n", current("new.org"))
        assertTrue(file("new.org").conflict)
        assertEquals("* TODO remote\n", repository.blobs.text(file("new.org").remoteBlob!!))
    }

    @Test
    fun disjointChangesMergeWithoutTouchingOtherBytes() = runBlocking {
        val before = file()
        repository.save(before.id, current().replace("first", "local"), before.contentBlob)
        remote.files["a.org"] = remote.files["a.org"]!!.replace("second", "remote")
        repository.sync(manual = true)
        assertEquals("* TODO 中文😀\nlocal\nremote\n", current())
        assertFalse(file().conflict)
        assertEquals(current(), remote.files["a.org"])
    }

    @Test
    fun editsDuringUploadAreNotAcknowledgedAsOldBytes() = runBlocking {
        val initial = file()
        repository.save(initial.id, "* TODO first upload\n", initial.contentBlob)
        remote.afterWrite = { path ->
            if (path == "a.org") {
                remote.afterWrite = null
                val current = file()
                repository.save(current.id, "* TODO later edit\n", current.contentBlob)
            }
        }
        repository.sync(pull = false)
        assertEquals("* TODO later edit\n", current())
        assertEquals(current(), remote.files["a.org"])
        assertNull(file().draftBlob)
    }

    @Test
    fun failedCrossFileTransactionDoesNotPartlyArchive() = runBlocking {
        repository.database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_source BEFORE UPDATE ON files WHEN OLD.path='a.org' BEGIN SELECT RAISE(ABORT, 'injected transaction failure'); END"
        )
        val item = repository.state.value.documents["a.org"]!!.headings.first()
        assertTrue(runCatching { repository.refile(item, "z.org") }.isFailure)
        assertFalse(repository.database.dao().files(file().workspaceId).any { it.path == "z.org" })
        assertEquals("* TODO 中文😀\nfirst\nsecond\n", current())
    }

    @Test
    fun archiveConfirmsDestinationBeforeRemovingSourceAndRetriesWithoutDuplicate() = runBlocking {
        val item = repository.state.value.documents["a.org"]!!.headings.first()
        repository.refile(item, "z.org")
        remote.failWrite = "z.org"
        assertTrue(runCatching { repository.sync(pull = false) }.isFailure)
        assertTrue(remote.files["a.org"]!!.contains("中文😀"))
        assertNull(remote.files["z.org"])
        remote.failWrite = null
        repository.sync(pull = false)
        repository.sync(manual = true)
        assertEquals("", remote.files["a.org"])
        assertEquals(1, Regex("中文😀").findAll(remote.files["z.org"]!!).count())
        assertTrue(remote.writes.indexOf("z.org") < remote.writes.indexOf("a.org"))
    }

    @Test
    fun interruptedMoveRecoversFromExactDestinationBytes() = runBlocking {
        repository.move(file().id, "renamed.org")
        remote.loseMoveResponse = true
        assertTrue(runCatching { repository.sync(pull = false) }.isFailure)
        repository.sync(manual = true)
        assertFalse(remote.files.containsKey("a.org"))
        assertTrue(remote.files.containsKey("renamed.org"))
        assertEquals(0, repository.state.value.operations.size)
        assertFalse(file("renamed.org").conflict)
    }

    @Test
    fun incompleteEnumerationNeverDeletesCachedChildren() = runBlocking {
        remote.files["folder/task.org"] = "* TODO retained\n"
        repository.sync(manual = true)
        remote.hidden = "folder"
        repository.sync(manual = true)
        assertEquals("* TODO retained\n", current("folder/task.org"))
    }

    @Test
    fun trashMetadataIsCompatibleAndRestoreNeverOverwritesNewFile() = runBlocking {
        repository.move(file().id, "a.org", "trash")
        repository.sync(pull = false)
        val infoPath = remote.files.keys.single { it.endsWith("/info.json") }
        val info =
            kotlinx.serialization.json.Json.parseToJsonElement(remote.files[infoPath]!!).toString()
        assertTrue(info.contains("originalPath"))
        assertTrue(info.contains("deletedAt"))
        val trashed = repository.state.value.files.single { it.trashed }
        assertTrue(trashed.remotePath.endsWith("/item"))
        repository.create("a.org", "* TODO replacement\n")
        assertTrue(runCatching { repository.restore(trashed) }.isFailure)
        repository.restore(trashed, "recovered.org")
        repository.sync(pull = false)
        assertTrue(remote.files["recovered.org"]!!.contains("中文😀"))
        assertEquals("* TODO replacement\n", remote.files["a.org"])
    }

    @Test
    fun trashCreatedByIosCanBeRecovered() = runBlocking {
        val id = "0767F603-7995-49B2-9A34-D4AAE9B8A3CD"
        remote.files[".orgenda-trash/$id/info.json"] =
            """{"id":"$id","originalPath":"old.org","title":"Old","kind":"org","deletedAt":800000000.0}"""
        remote.files[".orgenda-trash/$id/item"] = "* TODO iOS recovery\n"
        repository.sync(manual = true)
        val old = repository.state.value.files.single { it.path == "old.org" }
        assertTrue(old.trashed)
        assertEquals("* TODO iOS recovery\n", repository.text(old))
        repository.restore(old)
        repository.sync(pull = false)
        assertEquals("* TODO iOS recovery\n", remote.files["old.org"])
    }

    @Test
    fun folderMoveWithLostResponseRecoversAfterRestart() = runBlocking {
        remote.directories += "folder"
        remote.files["folder/one.org"] = "* TODO Original\n"
        repository.sync(manual = true)
        val folder = file("folder")
        repository.move(folder.id, "moved")
        remote.loseMoveResponse = true
        runCatching { repository.sync(pull = false) }
        assertEquals("* TODO Original\n", remote.files["moved/one.org"])
        repository.database.close()
        repository = WorkspaceRepository(context, backendOverride = { remote })
        repository.start()
        repository.sync(manual = true)
        assertTrue(repository.state.value.operations.isEmpty())
        assertEquals("* TODO Original\n", current("moved/one.org"))
        assertEquals(1, remote.files.keys.count { it.endsWith("one.org") })
    }

    @Test
    fun switchingRequiresSyncOrRecoveryOfCurrentDrafts() = runBlocking {
        val original = file()
        repository.save(original.id, "* TODO recover me\n", original.contentBlob)
        val second = WorkspaceEntity("second", "private", context.filesDir.path, "Second")
        repository.database.dao().putWorkspace(second)
        remote.offline = true
        assertTrue(runCatching { repository.switchWorkspace(second.id) }.isFailure)
        val output = java.io.ByteArrayOutputStream()
        repository.exportRecovery(output)
        val copies = linkedMapOf<String, String>()
        java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                copies[entry.name] = zip.readBytes().strictText()
            }
        }
        assertTrue(copies.values.contains("* TODO recover me\n"))
        assertTrue(copies.keys.any { it.endsWith("manifest.json") })
        runCatching { repository.switchWorkspace(second.id) }
        assertEquals(second.id, repository.state.value.workspace!!.id)
        assertEquals(
            "* TODO recover me\n",
            repository.blobs.text(repository.database.dao().file(original.id)!!.draftBlob!!),
        )
    }
}

private class MemoryBackend : StorageBackend {
    val files = linkedMapOf<String, String>()
    val writes = mutableListOf<String>()
    val directories = mutableSetOf<String>()
    var offline = false
    var hidden: String? = null
    var failWrite: String? = null
    var loseMoveResponse = false
    var afterWrite: (suspend (String) -> Unit)? = null

    private fun available() {
        if (offline) throw IOException("Offline")
    }

    private fun entry(path: String, text: String) = RemoteEntry(path, path, "\"${text.sha256()}\"")

    override suspend fun scan(): RemoteInventory {
        available()
        return RemoteInventory(
            files
                .filterKeys { hidden == null || !it.startsWith("$hidden/") }
                .map { entry(it.key, it.value) } +
                directories.map { RemoteEntry(it, directory = true) },
            hidden?.let { mapOf(it to "Unavailable") } ?: emptyMap(),
        )
    }

    override suspend fun read(path: String, limit: Int): RemoteContent {
        available()
        val text = files[path] ?: throw StorageFailure.Missing(path)
        return RemoteContent(entry(path, text), text.toByteArray())
    }

    override suspend fun write(
        path: String,
        bytes: ByteArray,
        expected: RemoteVersion?,
    ): RemoteContent {
        available()
        if (path == failWrite) throw IOException("Injected write failure")
        val old = files[path]
        if (expected == null && old != null || expected != null && old?.sha256() != expected.hash)
            throw StorageFailure.Conflict(path)
        val text = bytes.strictText()
        files[path] = text
        writes += path
        afterWrite?.invoke(path)
        return RemoteContent(entry(path, text), bytes)
    }

    override suspend fun mkdir(path: String) {
        available()
        directories += path
    }

    override suspend fun move(
        from: String,
        to: String,
        expected: RemoteVersion?,
        directory: Boolean,
    ) {
        available()
        if (directory) {
            if (from !in directories) throw StorageFailure.Missing(from)
            if (to in directories) throw StorageFailure.Conflict(to)
            val children = files.filterKeys { it.startsWith("$from/") }
            children.forEach { (path, text) ->
                files[to + path.removePrefix(from)] = text
                files.remove(path)
            }
            val folders = directories.filter { it == from || it.startsWith("$from/") }
            folders.forEach {
                directories.remove(it)
                directories.add(to + it.removePrefix(from))
            }
            if (loseMoveResponse) {
                loseMoveResponse = false
                throw IOException("Lost successful MOVE response")
            }
            return
        }
        val text = files[from] ?: throw StorageFailure.Missing(from)
        if (files.containsKey(to) || text.sha256() != expected?.hash)
            throw StorageFailure.Conflict(to)
        files[to] = text
        files.remove(from)
        if (loseMoveResponse) {
            loseMoveResponse = false
            throw IOException("Lost successful MOVE response")
        }
    }

    override suspend fun probe() {
        available()
    }
}
