package com.roifewu.orgenda.data

import com.roifewu.orgenda.core.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrivateFolderBackend(private val root: File) : StorageBackend {
    init {
        root.mkdirs()
    }

    private fun file(path: String): File {
        safePath(path, true)
        return File(root, path).canonicalFile.also {
            require(it.toPath().startsWith(root.canonicalFile.toPath()))
        }
    }

    override suspend fun scan(): RemoteInventory =
        withContext(Dispatchers.IO) {
            RemoteInventory(
                root
                    .walkTopDown()
                    .onEnter {
                        it == root ||
                            !it.name.startsWith('.') ||
                            it.relativeTo(root).invariantSeparatorsPath.startsWith(".orgenda-trash")
                    }
                    .filter { it != root }
                    .map {
                        val path = it.relativeTo(root).invariantSeparatorsPath
                        require(!Files.isSymbolicLink(it.toPath()))
                        RemoteEntry(path, path, directory = it.isDirectory, size = it.length())
                    }
                    .toList()
            )
        }

    override suspend fun read(path: String, limit: Int): RemoteContent =
        withContext(Dispatchers.IO) {
            val file = file(path)
            if (!file.exists()) throw StorageFailure.Missing(path)
            require(file.isFile && file.length() <= limit)
            val bytes = file.inputStream().use { it.readNBytes(limit + 1) }
            require(bytes.size <= limit)
            RemoteContent(
                RemoteEntry(path, path, "sha256:${bytes.sha256()}", size = bytes.size.toLong()),
                bytes,
            )
        }

    override suspend fun write(
        path: String,
        bytes: ByteArray,
        expected: RemoteVersion?,
    ): RemoteContent =
        withContext(Dispatchers.IO) {
            val target = file(path)
            if (target.exists()) {
                if (expected == null || read(path).hash != expected.hash)
                    throw StorageFailure.Conflict(path)
            } else if (expected != null) throw StorageFailure.Conflict(path)
            target.parentFile!!.mkdirs()
            val temp = File.createTempFile(".orgenda-", ".tmp", target.parentFile)
            try {
                FileOutputStream(temp).use {
                    it.write(bytes)
                    it.fd.sync()
                }
                if (expected == null) Files.move(temp.toPath(), target.toPath())
                else
                    Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
            } finally {
                temp.delete()
            }
            read(path)
        }

    override suspend fun mkdir(path: String) {
        val target = file(path)
        check(target.mkdirs() || target.isDirectory)
    }

    override suspend fun move(
        from: String,
        to: String,
        expected: RemoteVersion?,
        directory: Boolean,
    ) =
        withContext(Dispatchers.IO) {
            if (!directory && (expected == null || read(from).hash != expected.hash))
                throw StorageFailure.Conflict(from)
            val target = file(to)
            if (target.exists()) throw StorageFailure.Conflict(to)
            target.parentFile!!.mkdirs()
            Files.move(file(from).toPath(), target.toPath())
            Unit
        }

    override suspend fun probe() {
        require(root.isDirectory && root.canWrite())
    }
}
