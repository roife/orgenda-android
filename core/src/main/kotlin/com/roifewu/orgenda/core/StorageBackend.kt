package com.roifewu.orgenda.core

data class RemoteEntry(
    val path: String,
    val identity: String = path,
    val revision: String? = null,
    val directory: Boolean = false,
    val size: Long? = null,
)

data class RemoteContent(val entry: RemoteEntry, val bytes: ByteArray) {
    val hash
        get() = bytes.sha256()
}

data class RemoteVersion(val revision: String?, val hash: String)

data class RemoteInventory(
    val entries: List<RemoteEntry>,
    val unavailable: Map<String, String> = emptyMap(),
    val minimumPollSeconds: Long = 30,
)

sealed class StorageFailure(message: String) : Exception(message) {
    class Conflict(val path: String) : StorageFailure("Changed elsewhere: $path")

    class Authentication : StorageFailure("Update the WebDAV password. Local edits are safe.")

    class Unavailable(message: String = "Storage is unavailable. Local edits are safe.") :
        StorageFailure(message)

    class Throttled(val retrySeconds: Long) :
        StorageFailure("Server busy; retry after $retrySeconds seconds")

    class Unsupported(message: String) : StorageFailure(message)

    class Missing(val path: String) : StorageFailure("Missing: $path")
}

interface StorageBackend {
    suspend fun scan(): RemoteInventory

    suspend fun read(path: String, limit: Int = 64 * 1024 * 1024): RemoteContent

    suspend fun write(path: String, bytes: ByteArray, expected: RemoteVersion?): RemoteContent

    suspend fun mkdir(path: String)

    suspend fun move(from: String, to: String, expected: RemoteVersion?, directory: Boolean = false)

    suspend fun probe()
}

fun isWorkspaceText(path: String) =
    path.endsWith(".org") ||
        path.endsWith(".org_archive") ||
        path.endsWith(".md") ||
        path == "config.json"

fun isRecoveryPath(path: String) =
    path.split('/').any {
        it == ".orgenda-trash" || it == ".orgenda-recovery" || it == "Unsaved Edits"
    }
