package com.roifewu.orgenda.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.roifewu.orgenda.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class SafBackend(context: Context, private val tree: Uri) : StorageBackend {
    private val resolver = context.contentResolver
    private val root = DocumentFile.fromTreeUri(context, tree) ?: throw StorageFailure.Unavailable()
    private fun complete(cursor: android.database.Cursor) {
        if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) || cursor.extras.getString(DocumentsContract.EXTRA_ERROR) != null)
            throw StorageFailure.Unavailable("The folder provider has not finished loading")
    }
    private fun child(parent: DocumentFile, name: String): DocumentFile? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent.uri))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val cursor = resolver.query(uri, projection, null, null, null) ?: throw StorageFailure.Unavailable()
        return cursor.use {
            complete(it)
            var result: DocumentFile? = null
            while (it.moveToNext()) if (it.getString(1) == name) {
                if (result != null) throw StorageFailure.Conflict(name)
                result = DocumentFile.fromSingleUri(rootContext, DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0)))
            }
            result
        }
    }
    private val rootContext = context
    private fun locate(path: String): DocumentFile {
        safePath(path, true)
        if (!root.exists() || !root.canRead()) throw StorageFailure.Unavailable("Folder access was revoked or the provider is unavailable")
        var cursor = root
        if (path.isNotEmpty()) for (part in path.split('/')) cursor = child(cursor, part) ?: throw StorageFailure.Missing(path)
        return cursor
    }
    // DocumentFile's listFiles() silently swallows provider failures. Explicit query above preserves incomplete inventories.
    override suspend fun scan(): RemoteInventory = withContext(Dispatchers.IO) {
        val entries = mutableListOf<RemoteEntry>(); val unavailable = mutableMapOf<String, String>()
        val queue = ArrayDeque<Pair<Uri, String>>()
        queue.add(root.uri to "")
        while (queue.isNotEmpty()) {
            val (folder, prefix) = queue.removeFirst()
            try {
                val listUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
                val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
                val cursor = resolver.query(listUri, projection, null, null, null) ?: throw StorageFailure.Unavailable()
                cursor.use {
                    complete(it)
                    while (it.moveToNext()) {
                        val id = it.getString(0); val name = it.getString(1) ?: throw StorageFailure.Unavailable("Missing filename")
                        safePath(name); require('/' !in name)
                        val path = if (prefix.isEmpty()) name else "$prefix/$name"
                        val directory = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                        entries += RemoteEntry(path, id, directory = directory, size = if (it.isNull(3)) null else it.getLong(3))
                        if (directory && (!name.startsWith('.') || path == ".orgenda-trash" || path.startsWith(".orgenda-trash/"))) queue.add(DocumentsContract.buildDocumentUriUsingTree(tree, id) to path)
                    }
                }
                require(entries.size <= 100000) { "Workspace is too large" }
            } catch (e: Exception) {
                if (prefix.isEmpty()) throw StorageFailure.Unavailable(e.message ?: "Folder permission expired")
                unavailable[prefix] = e.message ?: "Unavailable"
            }
        }
        RemoteInventory(entries, unavailable)
    }
    override suspend fun read(path: String, limit: Int): RemoteContent = withContext(Dispatchers.IO) {
        val file = locate(path)
        require(file.isFile && file.length() <= limit) { "File exceeds download limit" }
        val input = resolver.openInputStream(file.uri) ?: throw StorageFailure.Unavailable()
        val bytes = input.use {
            val result = java.io.ByteArrayOutputStream(); val buffer = ByteArray(65536)
            while (true) { val n = it.read(buffer); if (n < 0) break; require(result.size().toLong() + n <= limit); result.write(buffer, 0, n) }
            result.toByteArray()
        }
        RemoteContent(RemoteEntry(path, DocumentsContract.getDocumentId(file.uri), "sha256:${bytes.sha256()}", size = bytes.size.toLong()), bytes)
    }
    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        safePath(path, true)
        var current = root
        if (path.isNotEmpty()) for (part in path.split('/')) {
            current = child(current, part)?.also { require(it.isDirectory) { "Destination is not a folder" } }
                ?: DocumentsContract.createDocument(resolver, current.uri, DocumentsContract.Document.MIME_TYPE_DIR, part)?.let { DocumentFile.fromSingleUri(rootContext, it) }
                ?: throw StorageFailure.Unsupported("Provider cannot create folders")
        }
    }
    private suspend fun create(path: String): DocumentFile {
        val parentPath = path.substringBeforeLast('/', ""); mkdir(parentPath)
        val parent = locate(parentPath); val name = path.substringAfterLast('/')
        if (child(parent, name) != null) throw StorageFailure.Conflict(path)
        val file = DocumentsContract.createDocument(resolver, parent.uri, "application/octet-stream", name)?.let { DocumentFile.fromSingleUri(rootContext, it) }
            ?: throw StorageFailure.Unsupported("Provider cannot create files")
        if (file.name != name) { file.delete(); throw StorageFailure.Conflict(path) }
        return file
    }
    private fun writeBytes(file: DocumentFile, bytes: ByteArray) {
        require(file.canWrite()) { "Folder is read-only" }
        val descriptor = resolver.openFileDescriptor(file.uri, "rwt") ?: throw StorageFailure.Unavailable()
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { stream -> stream.write(bytes); stream.flush(); runCatching { stream.fd.sync() } }
    }
    override suspend fun write(path: String, bytes: ByteArray, expected: RemoteVersion?): RemoteContent = withContext(Dispatchers.IO) {
        safePath(path)
        val file = if (expected == null) create(path) else {
            val old = read(path)
            if (old.hash != expected.hash) throw StorageFailure.Conflict(path)
            // SAF cannot lock external clients. Keep the exact pre-write version in the selected workspace.
            val recovery = ".orgenda-recovery/${UUID.randomUUID()}/${path.substringAfterLast('/')}"
            writeBytes(create(recovery), old.bytes)
            if (read(recovery).hash != old.hash) throw StorageFailure.Unavailable("Recovery copy could not be verified")
            if (read(path).hash != expected.hash) throw StorageFailure.Conflict(path)
            locate(path)
        }
        writeBytes(file, bytes)
        val result = read(path)
        if (!result.bytes.contentEquals(bytes)) throw StorageFailure.Conflict(path)
        result
    }
    override suspend fun move(from: String, to: String, expected: RemoteVersion?, directory: Boolean) = withContext(Dispatchers.IO) {
        safePath(from); safePath(to)
        require(from.substringBeforeLast('/', "") == to.substringBeforeLast('/', "") || from.substringAfterLast('/') == to.substringAfterLast('/')) { "SAF compound moves must be journaled as separate steps" }
        val source = locate(from)
        if (!directory && (expected == null || read(from).hash != expected.hash)) throw StorageFailure.Conflict(from)
        val parent = to.substringBeforeLast('/', ""); mkdir(parent)
        val targetParent = locate(parent)
        if (child(targetParent, to.substringAfterLast('/')) != null) throw StorageFailure.Conflict(to)
        var uri = source.uri
        if (parent != from.substringBeforeLast('/', "")) {
            val sourceParent = locate(from.substringBeforeLast('/', ""))
            uri = DocumentsContract.moveDocument(resolver, uri, sourceParent.uri, targetParent.uri)
                ?: throw StorageFailure.Unsupported("This provider cannot safely move documents")
        }
        if (from.substringAfterLast('/') != to.substringAfterLast('/')) {
            uri = DocumentsContract.renameDocument(resolver, uri, to.substringAfterLast('/'))
                ?: throw StorageFailure.Unsupported("This provider cannot rename documents")
        }
        if (!directory && expected != null && read(to).hash != expected.hash) throw StorageFailure.Conflict(to)
    }
    override suspend fun probe() = withContext(Dispatchers.IO) {
        require(root.exists() && root.isDirectory && root.canRead() && root.canWrite()) { "Select a writable workspace folder" }
        scan()
        Unit
    }
}
