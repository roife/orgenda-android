package com.roifewu.orgenda.platform

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.roifewu.orgenda.*
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.WorkspaceSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.time.*

@Serializable data class PlannedReminder(val id: String, val workspace: String, val path: String, val byte: Int, val title: String, val at: Long, val offset: Int)

class ReminderScheduler(private val context: Context) {
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private val preferences = context.getSharedPreferences("reminder-plan", Context.MODE_PRIVATE)
    private fun intent(id: String) = Intent(context, ReminderReceiver::class.java).setAction("orgenda.reminder").setData(Uri.parse("orgenda://reminder/$id"))
    private fun pending(id: String) = PendingIntent.getBroadcast(context, 0, intent(id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun authorized() = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun exact() = alarm.canScheduleExactAlarms()
    @Synchronized fun refresh(snapshot: WorkspaceSnapshot, enabled: Boolean, force: Boolean = false) {
        val now = ZonedDateTime.now()
        val plan = if (enabled && authorized()) snapshot.items.filter { !it.done }.flatMap { item ->
            item.stamps.filter { it.hasTime && (it.active || it.keyword in listOf("SCHEDULED", "DEADLINE")) && it.keyword != "CLOSED" }.flatMap { stamp ->
                var at = stamp.date.atZone(now.zone)
                if (at < now && stamp.repeater != null) at = runCatching { stamp.repeater!!.copy(mode = "++").next(at, now) }.getOrNull() ?: return@flatMap emptyList()
                val advance = item.properties["APPT_WARNTIME"]?.toIntOrNull()?.takeIf { it >= 0 }?.coerceAtMost(1440) ?: snapshot.configuration.reminders.advanceMinutes
                val offsets = (advance downTo 0 step snapshot.configuration.reminders.repeatMinutes.coerceAtLeast(1)).toSet() + 0
                offsets.mapNotNull { offset ->
                    val fire = at.minusMinutes(offset.toLong())
                    if (fire <= now) null else {
                        val workspace = snapshot.workspace!!.id
                        val id = "$workspace:${item.path}:${item.start}:${at.toEpochSecond()}:$offset".sha256()
                        PlannedReminder(id, workspace, item.path, item.start, item.title, fire.toInstant().toEpochMilli(), offset)
                    }
                }
            }
        }.distinctBy { it.id }.sortedBy { it.at }.take(60) else emptyList()
        val old = runCatching { Json.decodeFromString<List<PlannedReminder>>(preferences.getString("plan", "[]")!!) }.getOrDefault(emptyList())
        val changed = force || preferences.getBoolean("rebuild", false) || old != plan || preferences.getBoolean("exact", false) != exact()
        if (!changed) return
        old.forEach { alarm.cancel(pending(it.id)) }
        check(preferences.edit().putString("plan", Json.encodeToString(plan)).putBoolean("enabled", enabled).putBoolean("exact", exact()).putBoolean("rebuild", false).commit())
        plan.forEach {
            try {
                if (exact()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.at, pending(it.id))
                else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.at, pending(it.id))
            } catch (_: SecurityException) {
                // Permission may change between checking and registering an alarm.
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.at, pending(it.id))
                preferences.edit().putBoolean("exact", false).apply()
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("reminder-plan", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false) || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val id = intent.data?.lastPathSegment ?: return
        val plan = runCatching { Json.decodeFromString<List<PlannedReminder>>(prefs.getString("plan", "[]")!!) }.getOrDefault(emptyList())
        val item = plan.firstOrNull { it.id == id } ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("appointments", context.getString(R.string.reminders), NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        val open = PendingIntent.getActivity(context, id.hashCode(), Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .putExtra("workspace", item.workspace).putExtra("path", item.path).putExtra("byte", item.byte), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id.hashCode(), NotificationCompat.Builder(context, "appointments").setSmallIcon(R.drawable.ic_orgenda)
            .setContentTitle(item.title).setContentText(if (item.offset == 0) context.getString(R.string.starting_now) else context.getString(R.string.starts_in, item.offset))
            .setContentIntent(open).setAutoCancel(true).setSilent(true).build())
        SyncWorker.enqueue(context, item.workspace, true)
    }
}

class ScheduleRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        // Preserve old alarm identifiers so a time or timezone change can cancel them.
        context.getSharedPreferences("reminder-plan", Context.MODE_PRIVATE).edit().putBoolean("rebuild", true).commit()
        SyncWorker.enqueue(context, pull = true)
    }
}
