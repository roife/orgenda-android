@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.roifewu.orgenda.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.WorkspaceSnapshot
import java.time.LocalDate
import java.util.Locale

@Composable fun TaskRow(item: OrgHeading, onOpen: () -> Unit, onToggle: () -> Unit, onArchive: (() -> Unit)? = null, onRefile: (() -> Unit)? = null, draggable: Boolean = false, onShowFile: (() -> Unit)? = null, dragItem: OrgHeading = item, onReschedule: (() -> Unit)? = null, allowsSwipe: Boolean = true) {
    var menu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    val color = workflowColor(item)
    val context = LocalContext.current
    val today = LocalToday.current.date
    val completionKey = when {
        !item.done && item.agendaStamp?.repeater != null && (item.habit || item.kind == "event") -> "complete_occurrence"
        item.done -> "mark_incomplete"
        else -> "mark_complete"
    }
    val schedule = onReschedule?.takeIf { item.isTask && item.kind != "event" }
    val row: @Composable () -> Unit = { Box {
        ListItem(
            headlineContent = { Text(item.title, color = color, textDecoration = if (item.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null) },
            leadingContent = { if (item.canComplete) Checkbox(item.done, { onToggle() }, Modifier.semantics { contentDescription = context.label(completionKey) + ": " + item.title }) else Icon(Icons.Default.Event, null) },
            supportingContent = { Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    WorkflowIcon(item)
                    Text(listOfNotNull(item.state.takeIf { it.isNotEmpty() }?.let { item.workflow.style(it).label.ifEmpty { it } }, item.agendaStamp?.takeIf { it.hasTime }?.date?.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)), item.priority.takeIf { it.isNotEmpty() }?.let { "[#$it]" }).joinToString(" · "), color = color)
                }
                if (item.tags.isNotEmpty()) Text(item.tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelSmall)
                Text((item.path.removeSuffix(".org").split('/') + item.ancestors).joinToString(" ▸ "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                if (item.habit) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (6L downTo 0L).forEach { offset ->
                    val day = today.minusDays(offset)
                    Surface(color = if (day in item.completedDays) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small, modifier = Modifier.size(12.dp).semantics { contentDescription = "$day: " + context.label(if (day in item.completedDays) "completed" else "none") }) { }
                } }
            } },
            trailingContent = { Row { if (draggable) Icon(Icons.Default.DragIndicator, tr("drag_task"), Modifier.size(48.dp).orgDrag("task", dragItem).padding(12.dp)); IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, tr("more_actions")) } } },
            modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = { preview = true }).semantics {
                customActions = listOfNotNull(schedule?.let { CustomAccessibilityAction(context.label("reschedule")) { it(); true } }, onShowFile?.let { CustomAccessibilityAction(context.label("show_in_file")) { it(); true } })
            }
        )
        DropdownMenu(menu, { menu = false }) {
            if (item.canComplete) DropdownMenuItem(text = { Text(tr(completionKey)) }, onClick = { menu = false; onToggle() })
            DropdownMenuItem(text = { Text(tr("edit")) }, onClick = { menu = false; onOpen() })
            if (schedule != null) DropdownMenuItem(text = { Text(tr("reschedule")) }, onClick = { menu = false; schedule() })
            if (onShowFile != null) DropdownMenuItem(text = { Text(tr("show_in_file")) }, onClick = { menu = false; onShowFile() })
            if (onArchive != null) DropdownMenuItem(text = { Text(tr("archive")) }, onClick = { menu = false; onArchive() })
            if (onRefile != null) DropdownMenuItem(text = { Text(tr("refile")) }, onClick = { menu = false; onRefile() })
        }
    } }
    if (allowsSwipe) {
        val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
            when (value) { SwipeToDismissBoxValue.StartToEnd -> onToggle(); SwipeToDismissBoxValue.EndToStart -> if (schedule != null) schedule() else onOpen(); else -> Unit }; false
        })
        SwipeToDismissBox(swipe, enableDismissFromStartToEnd = item.canComplete, backgroundContent = {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxSize()) { Box(Modifier.padding(24.dp), contentAlignment = if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd) {
                Icon(if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Icons.Default.Check else if (schedule != null) Icons.Default.CalendarMonth else Icons.Default.Edit, null)
            } }
        }) { row() }
    } else row()
    if (preview) {
        ModalPresence()
        AlertDialog(onDismissRequest = { preview = false }, title = { Text(item.title) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(listOf(item.state, item.priority, item.tags.joinToString(" ")).filter { it.isNotEmpty() }.joinToString(" · "))
        item.stamps.forEach { Text((it.keyword?.plus(": ") ?: "") + it.raw) }
        if (item.body.isNotBlank()) Text(item.body, maxLines = 12)
        if (item.habit) HabitHistory(item)
        if (item.canComplete) TextButton(onClick = { preview = false; onToggle() }) { Text(tr(completionKey)) }
        if (schedule != null) TextButton(onClick = { preview = false; schedule() }) { Text(tr("reschedule")) }
        if (onShowFile != null) TextButton(onClick = { preview = false; onShowFile() }) { Text(tr("show_in_file")) }
    } }, confirmButton = { TextButton(onClick = { preview = false; onOpen() }) { Text(tr("edit")) } }, dismissButton = { TextButton(onClick = { preview = false }) { Text(tr("close")) } })
    }
}

@Composable fun HabitHistory(item: OrgHeading) {
    val context = LocalContext.current
    val today = LocalToday.current.date
    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()) { Text(tr("habit_history"), style = MaterialTheme.typography.labelMedium)
        for (week in 0..3) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            for (day in 0..6) {
                val date = today.minusDays((27 - week * 7 - day).toLong())
                Surface(color = if (date in item.completedDays) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f).heightIn(min = 32.dp).semantics { contentDescription = "$date: " + context.label(if (date in item.completedDays) "completed" else "none") }) {
                    Box(contentAlignment = Alignment.Center) { Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall, color = if (date in item.completedDays) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable fun AgendaScreen(snapshot: WorkspaceSnapshot, open: (OrgHeading) -> Unit, toggle: (OrgHeading) -> Unit, archive: (OrgHeading) -> Unit, refile: (OrgHeading) -> Unit,
    perspective: String, selectPerspective: (String) -> Unit, showFile: (OrgHeading) -> Unit, reschedule: (OrgHeading) -> Unit) {
    val context = LocalContext.current
    val currentDay = LocalToday.current
    val today = currentDay.date
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val openStates = snapshot.configuration.workflow.sequences.flatMap { it.process }.distinct()
    val perspectives = listOf("overview", "overdue", "unscheduled") + openStates + "completed"
    val displayedPerspective = perspective.takeIf { it in perspectives } ?: "overview"
    val perspectiveStates = rememberSaveableStateHolder()
    LaunchedEffect(perspective, perspectives) { if (perspective !in perspectives) selectPerspective("overview") }
    val openItems = snapshot.items.filter { it.isTask && !it.done }
    val unscheduled = openItems.filter { it.agendaStamp == null }.sortedWith(Agenda.priorityOrder)
    val overdue = openItems.filter { Agenda.overdue(it, today) }
    val groups = remember(snapshot.revision, displayedPerspective, currentDay, locale) {
        when (displayedPerspective) {
            "overview" -> buildList<Pair<String, List<OrgHeading>>> {
                if (overdue.isNotEmpty()) add(context.label("overdue") to overdue.sortedBy { it.agendaStamp?.date })
                (0L..6L).forEach { delta -> val day = today.plusDays(delta); val items = Agenda.on(snapshot.items, day); if (items.isNotEmpty()) add(day.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", locale)) to items) }
                val deadlines = openItems.filter { it.deadline?.date?.toLocalDate()?.let { date -> date > today && date <= today.plusDays(snapshot.configuration.reminders.deadlineWarningDays.toLong()) } == true }.sortedBy { it.deadline?.date }
                if (deadlines.isNotEmpty()) add(context.label("upcoming_deadlines") to deadlines)
                openStates.forEach { state -> val items = unscheduled.filter { it.state == state }; if (items.isNotEmpty()) add(snapshot.configuration.workflow.style(state).label.ifEmpty { state } to items) }
            }
            "overdue" -> overdue.groupBy { it.agendaStamp!!.date.toLocalDate() }.toSortedMap().map { it.key.toString() to it.value }
            "unscheduled" -> unscheduled.groupBy { it.path }.toSortedMap().toList()
            "completed" -> listOf(context.label("completed") to snapshot.items.filter { it.done }.sortedWith(Agenda.priorityOrder))
            else -> listOf(snapshot.configuration.workflow.style(displayedPerspective).label.ifEmpty { displayedPerspective } to openItems.filter { it.state == displayedPerspective }.sortedWith(Agenda.priorityOrder))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Choice(displayedPerspective, perspectives, "agenda", selectPerspective) { value ->
                when (value) { "overview", "overdue", "unscheduled", "completed" -> context.label(value); else -> snapshot.configuration.workflow.style(value).label.ifEmpty { value } }
            }
        }
        if (groups.all { it.second.isEmpty() }) EmptyState("no_tasks") else perspectiveStates.SaveableStateProvider(displayedPerspective) {
            LazyColumn(modifier = Modifier.testTag("agenda.list.$displayedPerspective"), contentPadding = PaddingValues(bottom = 96.dp)) {
                
                // The date scope is still shown when Overdue is the only populated group.
                for (index in 0..groups.size) {
                    if (displayedPerspective == "overview" && index == if (overdue.isEmpty()) 0 else 1) {
                        item(key = "date-scope") { Text(tr("next_seven_days"), Modifier.padding(16.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium) }
                        if ((0L..6L).all { Agenda.on(snapshot.items, today.plusDays(it)).isEmpty() }) item(key = "empty-date-scope") { Text(tr("nothing_next_seven_days"), Modifier.padding(16.dp)) }
                    }
                    if (index == groups.size) continue
                    val (title, rows) = groups[index]
                    stickyHeader(key = "header:$index") { Surface(color = MaterialTheme.colorScheme.surfaceContainer) { Text("$title · ${rows.size}", Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.titleSmall) } }
                    items(rows, key = { "$index:${it.id}" }) { item ->
                        val canonical = snapshot.items.firstOrNull { it.id == item.id } ?: item
                        TaskRow(item, { open(canonical) }, { toggle(canonical) }, { archive(canonical) }, { refile(canonical) }, onShowFile = { showFile(canonical) }, onReschedule = { reschedule(canonical) })
                        HorizontalDivider(Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
    }
}
