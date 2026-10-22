@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.roifewu.orgenda.ui

import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.core.*
import java.time.format.DateTimeFormatter

fun readableTimestamp(context: Context, source: String, compact: Boolean = false): String {
    val draft = PlanningDraft.parse(source) ?: return source.trim()
    val locale = context.resources.configuration.locales[0]
    val timeFormat =
        DateTimeFormatter.ofPattern(
            if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
            locale,
        )
    val dates =
        draft.timestamps.joinToString(" → ") { stamp ->
            val skeleton =
                if (stamp.date.year == java.time.LocalDate.now().year) "MMMEd" else "yMMMEd"
            val dateFormat =
                DateTimeFormatter.ofPattern(
                    android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton),
                    locale,
                )
            stamp.date.format(dateFormat) +
                if (stamp.time)
                    " " +
                        stamp.date.format(timeFormat) +
                        stamp.original.rangeEnd?.let { "–" + it.format(timeFormat) }.orEmpty()
                else ""
        }
    val details =
        draft.timestamps.flatMap { stamp ->
            val values = mutableListOf<String>()
            var trailing =
                stamp.original.raw
                    .drop(1)
                    .dropLast(1)
                    .replaceFirst(
                        Regex(
                            "^[0-9]{4}-[0-9]{2}-[0-9]{2}(?:[ \t]+[A-Za-z]+)?(?:[ \t]+[0-9]{2}:[0-9]{2}(?:-[0-9]{2}:[0-9]{2})?)?"
                        ),
                        "",
                    )
            stamp.original.repeater?.let { repeat ->
                fun interval(value: Long, unit: Char): String {
                    val measure =
                        when (unit) {
                            'h' -> android.icu.util.MeasureUnit.HOUR
                            'w' -> android.icu.util.MeasureUnit.WEEK
                            'm' -> android.icu.util.MeasureUnit.MONTH
                            'y' -> android.icu.util.MeasureUnit.YEAR
                            else -> android.icu.util.MeasureUnit.DAY
                        }
                    return android.icu.text.MeasureFormat.getInstance(
                            locale,
                            android.icu.text.MeasureFormat.FormatWidth.WIDE,
                        )
                        .formatMeasures(android.icu.util.Measure(value, measure))
                }
                var description =
                    if (compact)
                        context.getString(
                            com.roifewu.orgenda.R.string.repeat_every_interval,
                            interval(repeat.interval, repeat.unit),
                        )
                    else
                        context.label("repeat") +
                            " ${repeat.interval} " +
                            context.label("unit_${repeat.unit}")
                if (repeat.mode != "+")
                    description +=
                        " · " +
                            context.label(
                                if (compact)
                                    if (repeat.mode == "++") "repeat_skip_dates"
                                    else "repeat_after_completion"
                                else if (repeat.mode == "++") "repeat_catch_up"
                                else "repeat_restart"
                            )
                Regex("/([0-9]+)([hdwmy])").matchEntire(repeat.maximum)?.let { maximum ->
                    description +=
                        " · " +
                            if (compact)
                                context.getString(
                                    com.roifewu.orgenda.R.string.repeat_maximum_interval,
                                    interval(
                                        maximum.groupValues[1].toLong(),
                                        maximum.groupValues[2].single(),
                                    ),
                                )
                            else
                                context.label("habit_maximum") +
                                    ": ${maximum.groupValues[1]} " +
                                    context.label("unit_${maximum.groupValues[2]}")
                }
                values += description
                trailing = trailing.replace(repeat.token, "")
            }
            trailing.trim().takeIf { it.isNotEmpty() }?.let(values::add)
            values
        }
    return draft.keyword?.let { context.label(it.lowercase()) + ": " }.orEmpty() +
        dates +
        if (details.isNotEmpty()) " · " + details.joinToString(" · ") else ""
}

@Composable
fun TimestampControl(source: String, edit: () -> Unit) {
    val context = LocalContext.current
    var raw by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Box {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 28.dp)
                    .combinedClickable(onClick = edit, onLongClick = { menu = true }),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (raw) source.trim() else readableTimestamp(context, source, compact = true),
                    Modifier.weight(1f),
                    style =
                        if (raw)
                            DocumentTypography.caption.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        else DocumentTypography.caption,
                )
                Icon(Icons.Default.CalendarMonth, tr("edit"), Modifier.size(18.dp))
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text(tr(if (raw) "readable_date" else "raw_timestamp")) },
                onClick = {
                    raw = !raw
                    menu = false
                },
            )
        }
    }
}

@Composable
fun ClockPreview(source: String) {
    val context = LocalContext.current
    val duration = readableClockDuration(source)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val numberStyle = DocumentTypography.caption2.copy(fontFeatureSettings = "tnum")
    androidx.compose.foundation.text.selection.SelectionContainer {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 1.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.Schedule, null, Modifier.size(16.dp), tint = secondary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    tr(if (duration == null) "running_clock" else "clock"),
                    style =
                        DocumentTypography.caption2.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                        ),
                    color = secondary,
                )
                Text(readableClockInterval(context, source), style = numberStyle, color = secondary)
            }
            Text(
                duration ?: tr("running"),
                style =
                    numberStyle.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    ),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

internal fun readableClockInterval(context: Context, source: String): String {
    val timestamps = Regex("\\[[^]\\r\\n]+]").findAll(source).map { it.value }.toList()
    if (timestamps.isEmpty()) return source.trim()
    val range = timestamps.take(2).joinToString("--")
    val stamps = PlanningDraft.parse(range)?.timestamps ?: return range
    if (
        stamps.size == 2 &&
            stamps.all { it.time } &&
            stamps[0].date.toLocalDate() == stamps[1].date.toLocalDate()
    ) {
        val locale = context.resources.configuration.locales[0]
        val timeFormat =
            DateTimeFormatter.ofPattern(
                if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
                locale,
            )
        return readableTimestamp(context, timestamps[0], compact = true) +
            "–" +
            stamps[1].date.format(timeFormat)
    }
    return readableTimestamp(context, range, compact = true)
}

internal fun readableClockDuration(source: String): String? {
    val raw = source.substringAfter("=>", "").trim()
    if (raw.isEmpty()) return null
    val parts = raw.split(':')
    if (parts.size != 2) return raw
    val hours = parts[0].toLongOrNull() ?: return raw
    val minutes = parts[1].toLongOrNull() ?: return raw
    if (hours < 0 || minutes !in 0..59) return raw
    return if (hours == 0L) "${minutes}m"
    else if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
}
