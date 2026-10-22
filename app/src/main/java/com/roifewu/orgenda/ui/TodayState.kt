package com.roifewu.orgenda.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** A day and its timezone form one invalidation key for date-sensitive UI caches. */
data class TodayState(val date: LocalDate, val zone: ZoneId)

val LocalToday = compositionLocalOf { ZonedDateTime.now().let { TodayState(it.toLocalDate(), it.zone) } }
private val systemNow: () -> ZonedDateTime = { ZonedDateTime.now() }

@Composable fun TodayProvider(now: () -> ZonedDateTime = systemNow, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentNow by rememberUpdatedState(now)
    val today by produceState(now().let { TodayState(it.toLocalDate(), it.zone) }, context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            currentDayUpdates(todayRefreshEvents(context, lifecycle)) { currentNow() }.collect { value = it }
        }
    }
    CompositionLocalProvider(LocalToday provides today, content = content)
}

internal fun millisUntilNextDay(now: ZonedDateTime): Long = Duration.between(
    now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
).toMillis().coerceAtLeast(1L)

internal fun currentDayUpdates(refreshes: Flow<Unit>, now: () -> ZonedDateTime): Flow<TodayState> = channelFlow {
    refreshes.collectLatest {
        while (currentCoroutineContext().isActive) {
            val current = now()
            send(TodayState(current.toLocalDate(), current.zone))
            // Recompute the next local midnight after every clock/zone change; a day is not always 24 hours.
            delay(millisUntilNextDay(current))
        }
    }
}.distinctUntilChanged()

internal fun todayRefreshEvents(context: Context, lifecycle: Lifecycle): Flow<Unit> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { trySend(Unit) }
    }
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) trySend(Unit) }
    context.registerReceiver(receiver, IntentFilter().apply {
        addAction(Intent.ACTION_DATE_CHANGED)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }, Context.RECEIVER_NOT_EXPORTED)
    lifecycle.addObserver(observer)
    trySend(Unit)
    awaitClose { context.unregisterReceiver(receiver); lifecycle.removeObserver(observer) }
}
