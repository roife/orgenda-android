package com.roifewu.orgenda.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TodayStateTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun midnightDelayUsesLocalDayAcrossDaylightSavingChanges() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(23 * 60 * 60 * 1000L, millisUntilNextDay(ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, zone)))
        assertEquals(25 * 60 * 60 * 1000L, millisUntilNextDay(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, zone)))
    }

    @Test fun visibleDayAdvancesAtMidnightWithoutAWorkspaceChange() = runBlocking {
        var time = ZonedDateTime.parse("2026-10-22T23:59:59.800+08:00[Asia/Taipei]")
        val refreshes = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val days = Channel<TodayState>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Unconfined) { currentDayUpdates(refreshes) { time }.collect { days.send(it) } }
        try {
            assertEquals(time.toLocalDate(), withTimeout(5000) { days.receive() }.date)
            time = time.plusNanos(200_000_000)
            assertEquals(time.toLocalDate(), withTimeout(5000) { days.receive() }.date)
        } finally { job.cancelAndJoin() }
    }

    @Test fun systemClockBroadcastsAndForegroundResumeRefreshTheDayAndZone() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        // AGP's binary manifest registers these even with Config.NONE. Isolate the UI receiver fixture.
        val backgroundReceivers = setOf(
            "com.roifewu.orgenda.platform.ScheduleRecoveryReceiver",
            "androidx.work.impl.background.systemalarm.RescheduleReceiver"
        )
        shadowOf(context).registeredReceivers.filter { it.broadcastReceiver.javaClass.name in backgroundReceivers }
            .forEach { it.context.unregisterReceiver(it.broadcastReceiver) }
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        var time = ZonedDateTime.parse("2026-10-23T00:30:00+08:00[Asia/Taipei]")
        val days = Channel<TodayState>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Unconfined) {
            currentDayUpdates(todayRefreshEvents(context, owner.lifecycle)) { time }.collect { days.send(it) }
        }
        suspend fun next() = withTimeout(5000) { days.receive() }
        try {
            assertEquals(TodayState(time.toLocalDate(), time.zone), next())
            assertEquals(1, shadowOf(context).getReceiversForIntent(Intent(Intent.ACTION_TIMEZONE_CHANGED)).size)
            time = time.withZoneSameInstant(ZoneId.of("America/Los_Angeles"))
            context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(TodayState(time.toLocalDate(), time.zone), next())
            time = time.plusDays(1)
            context.sendBroadcast(Intent(Intent.ACTION_DATE_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(time.toLocalDate(), next().date)
            time = time.minusDays(2)
            context.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(time.toLocalDate(), next().date)
            owner.registry.currentState = Lifecycle.State.CREATED
            time = time.plusDays(3)
            owner.registry.currentState = Lifecycle.State.RESUMED
            assertEquals(time.toLocalDate(), next().date)
        } finally { job.cancelAndJoin() }
        assertFalse(shadowOf(context).hasReceiverForIntent(Intent(Intent.ACTION_TIMEZONE_CHANGED)))
    }
}
