package com.roifewu.orgenda

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.roifewu.orgenda.data.WorkspaceRepository
import com.roifewu.orgenda.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class OrgendaApplication : Application() {
    lateinit var repository: WorkspaceRepository
    lateinit var settings: LocalSettings
    lateinit var reminders: ReminderScheduler
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var ready: Deferred<Unit>
    override fun onCreate() {
        super.onCreate()
        repository = WorkspaceRepository(this)
        settings = LocalSettings(this)
        reminders = ReminderScheduler(this)
        ready = scope.async { repository.start() }
        scope.launch {
            combine(repository.state, settings.state) { workspace, local -> workspace to local }.collectLatest { (workspace, local) ->
                delay(300)
                runCatching { reminders.refresh(workspace, local.notifications) }.onFailure(repository::error)
            }
        }
        SyncWorker.periodic(this)
        val shortcuts = listOf("capture" to R.string.new_task, "today" to R.string.today, "search" to R.string.search, "files" to R.string.files)
        getSystemService(ShortcutManager::class.java).dynamicShortcuts = shortcuts.map { (id, label) ->
            ShortcutInfo.Builder(this, id).setShortLabel(getString(label)).setIcon(Icon.createWithResource(this, R.drawable.ic_orgenda))
                .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("action", id)).build()
        }
    }
}

data class LocalPreferences(val theme: String = "system", val notifications: Boolean = false)
class LocalSettings(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(LocalPreferences(preferences.getString("theme", "system")!!, preferences.getBoolean("notifications", false)))
    val state = mutable.asStateFlow()
    fun theme(value: String) { preferences.edit().putString("theme", value).apply(); mutable.update { it.copy(theme = value) } }
    fun notifications(value: Boolean) { preferences.edit().putBoolean("notifications", value).apply(); mutable.update { it.copy(notifications = value) } }
}
