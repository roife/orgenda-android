package com.roifewu.orgenda.data

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import com.roifewu.orgenda.core.*

@Entity(tableName = "workspaces")
data class WorkspaceEntity(@PrimaryKey val id: String, val kind: String, val root: String, val name: String, val username: String = "", val lastSync: Long = 0, val retryAfter: Long = 0)

@kotlinx.serialization.Serializable
@Entity(tableName = "files", indices = [Index(value = ["workspaceId", "folded"], unique = true)])
data class FileEntity(
    @PrimaryKey val id: String, val workspaceId: String, val path: String, val folded: String = foldedPath(path),
    val remotePath: String = path, val remoteIdentity: String? = null, val revision: String? = null,
    val baseBlob: String? = null, val draftBlob: String? = null, val directory: Boolean = false,
    val conflict: Boolean = false, val remoteBlob: String? = null, val remoteMissing: Boolean = false,
    val trashed: Boolean = false, val deletedAt: Long = 0,
    val dependencyId: String? = null, val dependencyBlob: String? = null,
    val pinned: Boolean = false, val openedAt: Long = 0, val readingByte: Int = 0,
    val generation: Long = 0
) { val contentBlob get() = draftBlob ?: baseBlob }

@kotlinx.serialization.Serializable
@Entity(tableName = "operations", indices = [Index("workspaceId")])
data class OperationEntity(@PrimaryKey val id: String, val workspaceId: String, val fileId: String, val kind: String,
    val from: String, val to: String, val expectedBlob: String?, val expectedRevision: String?, val directory: Boolean,
    val sequence: Long, val error: String? = null)

@Entity(tableName = "attachments", primaryKeys = ["workspaceId", "path"])
data class AttachmentEntity(val workspaceId: String, val path: String, val blob: String, val fetchedAt: Long)

@Dao interface WorkspaceDao {
    @Query("SELECT * FROM workspaces") suspend fun workspaces(): List<WorkspaceEntity>
    @Query("SELECT * FROM workspaces WHERE id=:id") suspend fun workspace(id: String): WorkspaceEntity?
    @Upsert suspend fun putWorkspace(value: WorkspaceEntity)
    @Query("SELECT * FROM files WHERE workspaceId=:workspaceId ORDER BY path") suspend fun files(workspaceId: String): List<FileEntity>
    @Query("SELECT * FROM files WHERE id=:id") suspend fun file(id: String): FileEntity?
    @Query("SELECT * FROM files WHERE workspaceId=:workspaceId AND folded=:folded LIMIT 1") suspend fun path(workspaceId: String, folded: String): FileEntity?
    @Upsert suspend fun put(value: FileEntity)
    @Upsert suspend fun putAll(values: List<FileEntity>)
    @Query("DELETE FROM files WHERE id=:id") suspend fun remove(id: String)
    @Query("SELECT * FROM operations WHERE workspaceId=:workspaceId ORDER BY sequence") suspend fun operations(workspaceId: String): List<OperationEntity>
    @Upsert suspend fun putOperation(value: OperationEntity)
    @Query("DELETE FROM operations WHERE id=:id") suspend fun removeOperation(id: String)
    @Query("SELECT * FROM attachments WHERE workspaceId=:workspaceId AND path=:path") suspend fun attachment(workspaceId: String, path: String): AttachmentEntity?
    @Upsert suspend fun putAttachment(value: AttachmentEntity)
}

@Database(entities = [WorkspaceEntity::class, FileEntity::class, OperationEntity::class, AttachmentEntity::class], version = 1, exportSchema = true)
abstract class WorkspaceDatabase : RoomDatabase() {
    abstract fun dao(): WorkspaceDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, WorkspaceDatabase::class.java, "state.sqlite")
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL"); db.query("PRAGMA busy_timeout=2500").use { it.moveToFirst() } }
            }).build()
    }
}

class BlobStore(private val root: File) {
    init { root.mkdirs() }
    @Synchronized fun put(bytes: ByteArray): String {
        val hash = bytes.sha256(); val target = File(root, hash)
        if (target.exists() && target.readBytes().sha256() == hash) return hash
        val temporary = File.createTempFile("blob-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            check(temporary.renameTo(target)) { "Could not commit document bytes" }
            // Persist the directory entry before SQLite can refer to this blob.
            val fd = android.system.Os.open(root.path, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        } finally { temporary.delete() }
        return hash
    }
    fun put(text: String) = put(text.utf8Bytes())
    fun read(hash: String): ByteArray {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        val bytes = File(root, hash).readBytes()
        check(bytes.sha256() == hash) { "Damaged local document; recovery data has been preserved" }
        return bytes
    }
    fun text(hash: String): String = read(hash).strictText()
}
