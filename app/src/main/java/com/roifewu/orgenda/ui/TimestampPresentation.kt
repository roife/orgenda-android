@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.roifewu.orgenda.ui

import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.core.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun readableTimestamp(context: Context, source: String): String {
    val draft = PlanningDraft.parse(source) ?: return source.trim()
    val locale = context.resources.configuration.locales[0]
    val timeFormat = DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", locale)
    val dates = draft.timestamps.joinToString(" → ") { stamp ->
        val skeleton = if (stamp.date.year == java.time.LocalDate.now().year) "MMMEd" else "yMMMEd"
        val dateFormat = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        stamp.date.format(dateFormat) + if (stamp.time) " " + stamp.date.format(timeFormat) + stamp.original.rangeEnd?.let { "–" + it.format(timeFormat) }.orEmpty() else ""
    }
    val details = draft.timestamps.flatMap { stamp ->
        val values = mutableListOf<String>()
        var trailing = stamp.original.raw.drop(1).dropLast(1)
            .replaceFirst(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}(?:[ \t]+[A-Za-z]+)?(?:[ \t]+[0-9]{2}:[0-9]{2}(?:-[0-9]{2}:[0-9]{2})?)?"), "")
        stamp.original.repeater?.let { repeat ->
            var description = context.label("repeat") + " ${repeat.interval} " + context.label("unit_${repeat.unit}")
            if (repeat.mode != "+") description += " · " + context.label(if (repeat.mode == "++") "repeat_catch_up" else "repeat_restart")
            Regex("/([0-9]+)([hdwmy])").matchEntire(repeat.maximum)?.let { maximum ->
                description += " · " + context.label("habit_maximum") + ": ${maximum.groupValues[1]} " + context.label("unit_${maximum.groupValues[2]}")
            }
            values += description
            trailing = trailing.replace(repeat.token, "")
        }
        trailing.trim().takeIf { it.isNotEmpty() }?.let(values::add)
        values
    }
    return draft.keyword?.let { context.label(it.lowercase()) + ": " }.orEmpty() + dates + if (details.isNotEmpty()) " · " + details.joinToString(" · ") else ""

}
@Composable fun TimestampControl(source: String, edit: () -> Unit) {
    val context = LocalContext.current
    var raw by remember { mutableStateOf(false) }; var menu by remember { mutableStateOf(false) }
    Box {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).combinedClickable(onClick = edit, onLongClick = { menu = true })) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (raw) source.trim() else readableTimestamp(context, source), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Icon(Icons.Default.CalendarMonth, tr("edit")) }
        }
        DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text(tr(if (raw) "readable_date" else "raw_timestamp")) }, onClick = { raw = !raw; menu = false }) }
    }
}
@Composable fun ClockPreview(source: String) {
    val context = LocalContext.current
    val range = Regex("\\[[^]]+]--\\[[^]]+]").find(source)?.value ?: Regex("\\[[^]]+]").find(source)?.value
    val duration = source.substringAfter("=>", "").trim()
    androidx.compose.foundation.text.selection.SelectionContainer { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Schedule, null); Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) { Text(tr(if (duration.isEmpty()) "running_clock" else "clock"), style = MaterialTheme.typography.labelMedium); Text(range?.let { readableTimestamp(context, it) } ?: source, style = MaterialTheme.typography.bodySmall) }
        if (duration.isNotEmpty()) Text(duration, style = MaterialTheme.typography.labelLarge)
    } }
}
