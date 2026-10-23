package com.roifewu.orgenda

import android.content.SharedPreferences
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.FileEntity
import com.roifewu.orgenda.data.WorkspaceSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ConfigurationEdit(
    val workspaceId: String,
    val value: WorkspaceConfig,
    val raw: String,
    val baseline: String?,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

@Serializable private data class ConfigurationRecovery(val raw: String, val baseline: String?)

private val configurationDraftJson = Json {
    encodeDefaults = true
    prettyPrint = true
}

internal class ConfigurationEditor(
    private val workspace: StateFlow<WorkspaceSnapshot>,
    private val saveConfiguration: suspend (WorkspaceConfig, String?, String) -> FileEntity,
    private val scope: CoroutineScope,
    private val recovery: SharedPreferences,
    private val onSaved: (String) -> Unit,
) {
    private val saveMutex = Mutex()
    private var autosave: Job? = null
    private val mutableState = MutableStateFlow<ConfigurationEdit?>(null)
    val state = mutableState.asStateFlow()

    fun accept(snapshot: WorkspaceSnapshot) {
        val workspaceId = snapshot.workspace?.id ?: return
        val draft = mutableState.value
        val baseline =
            snapshot.files
                .firstOrNull { it.path == "config.json" && !it.trashed }
                ?.contentBlob
        if (draft?.workspaceId != workspaceId) {
            val recovered =
                recovery.getString(workspaceId, null)?.let {
                    runCatching { Json.decodeFromString<ConfigurationRecovery>(it) }
                        .getOrNull()
                }
            val value =
                recovered?.let {
                    runCatching { ConfigurationCodec.decode(it.raw) }.getOrNull()
                } ?: snapshot.configuration
            mutableState.value =
                ConfigurationEdit(
                    workspaceId,
                    value,
                    recovered?.raw ?: ConfigurationCodec.encode(snapshot.configuration),
                    if (recovered != null) recovered.baseline else baseline,
                    recovered != null,
                )
        } else if (!draft.dirty && !draft.saving)
            mutableState.value =
                draft.copy(
                    value = snapshot.configuration,
                    raw = ConfigurationCodec.encode(snapshot.configuration),
                    baseline = baseline,
                )
    }

    fun configure(value: WorkspaceConfig) {
        val old = mutableState.value ?: return
        changed(
            old.copy(
                value = value,
                raw = configurationDraftJson.encodeToString(value),
                dirty = true,
                error = null,
            )
        )
    }

    fun configureRaw(raw: String) {
        val old = mutableState.value ?: return
        val value = runCatching { ConfigurationCodec.decode(raw) }.getOrNull() ?: old.value
        changed(old.copy(raw = raw, value = value, dirty = true, error = null))
    }

    private fun changed(next: ConfigurationEdit) {
        mutableState.value = next
        persistRecovery(next)
        autosave?.cancel()
        autosave = scope.launch {
            delay(500)
            withContext(NonCancellable) { runCatching { saveNow() } }
        }
    }

    private fun persistRecovery(draft: ConfigurationEdit) {
        val change = recovery.edit()
        if (draft.dirty)
            change.putString(
                draft.workspaceId,
                Json.encodeToString(ConfigurationRecovery(draft.raw, draft.baseline)),
            )
        else change.remove(draft.workspaceId)
        change.commit()
    }

    private fun saveFailed(workspaceId: String, error: Exception) {
        mutableState.update {
            if (it?.workspaceId == workspaceId) it.copy(saving = false, error = error.message)
            else it
        }
    }

    suspend fun saveNow() = saveMutex.withLock {
        val draft = mutableState.value ?: return@withLock
        if (!draft.dirty) return@withLock
        mutableState.value = draft.copy(saving = true)
        try {
            val config = ConfigurationCodec.decode(draft.raw)
            val saved = saveConfiguration(config, draft.baseline, draft.workspaceId)
            val latest = mutableState.value
            if (latest?.workspaceId == draft.workspaceId) {
                val updated =
                    latest.copy(
                        value = if (latest.raw == draft.raw) config else latest.value,
                        baseline = saved.contentBlob,
                        dirty = latest.raw != draft.raw,
                        saving = false,
                        error = null,
                    )
                mutableState.value = updated
                persistRecovery(updated)
            }
            onSaved(saved.workspaceId)
        } catch (e: Exception) {
            saveFailed(draft.workspaceId, e)
            throw e
        }
    }

    suspend fun saveEdit(
        value: WorkspaceConfig,
        expected: WorkspaceConfig,
        expectedWorkspaceId: String,
    ) =
        withContext(NonCancellable + Dispatchers.IO) {
            saveMutex.withLock {
                val draft =
                    mutableState.value ?: error("Workspace configuration is not ready")
                require(
                    workspace.value.workspace?.id == expectedWorkspaceId &&
                        draft.workspaceId == expectedWorkspaceId
                ) {
                    "Workspace changed; reopen the editor before saving"
                }
                val candidateRaw = ConfigurationCodec.encode(value)
                val current = ConfigurationCodec.decode(draft.raw)
                // A recreated sheet may resume a request whose local commit already finished.
                if (!draft.dirty && current == value) return@withLock
                require(current == expected) {
                    "Configuration changed; reopen the editor before saving"
                }
                autosave?.cancel()
                mutableState.value = draft.copy(saving = true, error = null)
                try {
                    val saved =
                        saveConfiguration(value, draft.baseline, expectedWorkspaceId)
                    mutableState.update { latest ->
                        if (latest?.workspaceId != expectedWorkspaceId) latest
                        else if (latest.raw == draft.raw)
                            latest.copy(
                                value = value,
                                raw = candidateRaw,
                                baseline = saved.contentBlob,
                                dirty = false,
                                saving = false,
                                error = null,
                            )
                        else latest.copy(baseline = saved.contentBlob, saving = false, error = null)
                    }
                    val latest = mutableState.value
                    if (latest?.workspaceId == expectedWorkspaceId) persistRecovery(latest)
                    onSaved(saved.workspaceId)
                } catch (e: Exception) {
                    saveFailed(expectedWorkspaceId, e)
                    throw e
                }
            }
        }

    fun discard() {
        autosave?.cancel()
        val snapshot = workspace.value
        val id = snapshot.workspace?.id ?: return
        recovery.edit().remove(id).commit()
        mutableState.value =
            ConfigurationEdit(
                id,
                snapshot.configuration,
                ConfigurationCodec.encode(snapshot.configuration),
                snapshot.files.firstOrNull { it.path == "config.json" }?.contentBlob,
            )
    }
}
