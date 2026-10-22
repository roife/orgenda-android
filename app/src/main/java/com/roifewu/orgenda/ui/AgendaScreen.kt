@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.roifewu.orgenda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.WorkspaceSnapshot
import java.util.Locale

@Composable
fun TaskRow(
    item: OrgHeading,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onArchive: (() -> Unit)? = null,
    onRefile: (() -> Unit)? = null,
    draggable: Boolean = false,
    onShowFile: (() -> Unit)? = null,
    dragItem: OrgHeading = item,
    onReschedule: (() -> Unit)? = null,
    allowsSwipe: Boolean = true,
) {
    var menu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    val color = workflowColor(item)
    val titleColor =
        if (
            !item.isTask ||
                item.state == "TODO" && item.workflow.style(item.state).color == "default"
        )
            MaterialTheme.colorScheme.onSurface
        else color
    val context = LocalContext.current
    val today = LocalToday.current.date
    val stateLabel = item.workflow.style(item.state).label.ifEmpty { item.state }
    val time =
        item.agendaStamp
            ?.takeIf { it.hasTime }
            ?.date
            ?.format(
                java.time.format.DateTimeFormatter.ofPattern(
                    if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm"
                    else "h:mm a",
                    LocalConfiguration.current.locales[0] ?: Locale.getDefault(),
                )
            )
    val sourcePath =
        (item.path.removeSuffix(".org").split('/') + item.ancestors).joinToString(" ▸ ")
    val completionKey =
        when {
            !item.done &&
                item.agendaStamp?.repeater != null &&
                (item.habit || item.kind == "event") -> "complete_occurrence"
            item.done -> "mark_incomplete"
            else -> "mark_complete"
        }
    val schedule = onReschedule?.takeIf { item.isTask && item.kind != "event" }
    val row: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(onClick = onOpen, onLongClick = { preview = true })
                .semantics {
                    customActions =
                        listOfNotNull(
                            schedule?.let {
                                CustomAccessibilityAction(context.label("reschedule")) {
                                    it()
                                    true
                                }
                            },
                            onShowFile?.let {
                                CustomAccessibilityAction(context.label("show_in_file")) {
                                    it()
                                    true
                                }
                            },
                        )
                }
                .heightIn(min = 72.dp)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val status: @Composable () -> Unit = {
                if (item.isTask) WorkflowIcon(item, Modifier.size(21.dp))
                else
                    Icon(
                        when (item.kind) {
                            "habit" -> Icons.Default.Repeat
                            "project" -> Icons.Default.AccountTree
                            "note" -> Icons.Default.Description
                            else ->
                                if (item.canComplete) Icons.Default.CheckCircleOutline
                                else Icons.Default.Event
                        },
                        null,
                        Modifier.size(21.dp),
                        tint =
                            if (item.kind == "habit") workflowColor("green")
                            else MaterialTheme.colorScheme.primary,
                    )
            }
            if (item.canComplete)
                IconButton(
                    onClick = onToggle,
                    modifier =
                        Modifier.size(48.dp)
                            .then(if (draggable) Modifier.orgDrag("task", dragItem) else Modifier)
                            .testTag("agenda.item.complete.${item.id}")
                            .semantics {
                                contentDescription =
                                    context.label(completionKey) + ": " + item.title
                                if (item.isTask) stateDescription = stateLabel
                            },
                ) {
                    status()
                }
            else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { status() }
            Column(
                Modifier.weight(1f).padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (item.priority.isNotEmpty()) {
                        val priorityColor =
                            workflowColor(
                                when (item.priority) {
                                    "A" -> "red"
                                    "B" -> "orange"
                                    "C" -> "blue"
                                    else -> "gray"
                                }
                            )
                        Text(
                            item.priority,
                            Modifier.alignByBaseline()
                                .background(
                                    priorityColor.copy(alpha = 0.10f),
                                    RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                .semantics {
                                    contentDescription =
                                        context.label("priority") + ": " + item.priority
                                },
                            style = MaterialTheme.typography.labelMedium,
                            color = priorityColor,
                        )
                    }
                    Text(
                        item.title,
                        Modifier.weight(1f)
                            .alignByBaseline()
                            .testTag("agenda.item.title.${item.id}"),
                        style =
                            MaterialTheme.typography.bodyLarge.copy(
                                fontWeight =
                                    if (item.kind == "event") FontWeight.Normal
                                    else FontWeight.Medium
                            ),
                        color = titleColor,
                        textDecoration =
                            if (item.done) androidx.compose.ui.text.style.TextDecoration.LineThrough
                            else null,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (time != null)
                        Text(
                            time,
                            Modifier.alignByBaseline(),
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFeatureSettings = "tnum"
                                ),
                            color =
                                if (Agenda.overdue(item, today)) workflowColor("pink")
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    Text(
                        sourcePath,
                        Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.tags.isNotEmpty())
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item.tags.forEach { tag ->
                            Text(
                                tag,
                                Modifier.background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        MaterialTheme.shapes.small,
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                if (item.habit)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        (0L..6L).forEach { offset ->
                            val day = today.minusDays(offset)
                            Surface(
                                color =
                                    if (day in item.completedDays) workflowColor("green")
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.18f
                                        ),
                                shape = RoundedCornerShape(1.5.dp),
                                modifier =
                                    Modifier.width(5.dp).height(10.dp).semantics {
                                        contentDescription =
                                            "$day: " +
                                                context.label(
                                                    if (day in item.completedDays) "completed"
                                                    else "none"
                                                )
                                    },
                            ) {}
                        }
                    }
            }
            if (draggable)
                Icon(
                    Icons.Default.DragIndicator,
                    tr("drag_task"),
                    Modifier.size(48.dp).orgDrag("task", dragItem).padding(12.dp),
                    tint = mutedControlColor(),
                )
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.MoreVert, tr("more_actions"), tint = mutedControlColor())
                }
                DropdownMenu(menu, { menu = false }) {
                    if (item.canComplete)
                        DropdownMenuItem(
                            text = { Text(tr(completionKey)) },
                            onClick = {
                                menu = false
                                onToggle()
                            },
                        )
                    DropdownMenuItem(
                        text = { Text(tr("edit")) },
                        onClick = {
                            menu = false
                            onOpen()
                        },
                    )
                    if (schedule != null)
                        DropdownMenuItem(
                            text = { Text(tr("reschedule")) },
                            onClick = {
                                menu = false
                                schedule()
                            },
                        )
                    if (onShowFile != null)
                        DropdownMenuItem(
                            text = { Text(tr("show_in_file")) },
                            onClick = {
                                menu = false
                                onShowFile()
                            },
                        )
                    if (onArchive != null)
                        DropdownMenuItem(
                            text = { Text(tr("archive")) },
                            onClick = {
                                menu = false
                                onArchive()
                            },
                        )
                    if (onRefile != null)
                        DropdownMenuItem(
                            text = { Text(tr("refile")) },
                            onClick = {
                                menu = false
                                onRefile()
                            },
                        )
                }
            }
        }
    }
    if (allowsSwipe) {
        SwipeActionsRow(
            leading =
                if (item.canComplete)
                    listOf(
                        SwipeAction(
                            "agenda.swipe.complete",
                            tr(if (item.done) "swipe_reopen" else "swipe_complete"),
                            if (item.done) Icons.AutoMirrored.Filled.Undo else Icons.Default.Check,
                            invoke = onToggle,
                        )
                    )
                else emptyList(),
            trailing =
                listOfNotNull(
                    schedule?.let {
                        SwipeAction(
                            "agenda.swipe.reschedule",
                            tr("reschedule"),
                            Icons.Default.CalendarMonth,
                            invoke = it,
                        )
                    },
                    SwipeAction(
                        "agenda.swipe.edit",
                        tr("edit"),
                        Icons.Default.Edit,
                        invoke = onOpen,
                    ),
                ),
            modifier = Modifier.testTag("agenda.row.${item.id}"),
            fullSwipeLeading = true,
            content = row,
        )
    } else row()
    if (preview) {
        ModalPresence()
        AlertDialog(
            onDismissRequest = { preview = false },
            title = { Text(item.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        listOf(item.state, item.priority, item.tags.joinToString(" "))
                            .filter { it.isNotEmpty() }
                            .joinToString(" · ")
                    )
                    item.stamps.forEach { Text((it.keyword?.plus(": ") ?: "") + it.raw) }
                    if (item.body.isNotBlank()) Text(item.body, maxLines = 12)
                    if (item.habit) HabitHistory(item)
                    if (item.canComplete)
                        TextButton(
                            onClick = {
                                preview = false
                                onToggle()
                            }
                        ) {
                            Text(tr(completionKey))
                        }
                    if (schedule != null)
                        TextButton(
                            onClick = {
                                preview = false
                                schedule()
                            }
                        ) {
                            Text(tr("reschedule"))
                        }
                    if (onShowFile != null)
                        TextButton(
                            onClick = {
                                preview = false
                                onShowFile()
                            }
                        ) {
                            Text(tr("show_in_file"))
                        }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        preview = false
                        onOpen()
                    }
                ) {
                    Text(tr("edit"))
                }
            },
            dismissButton = { TextButton(onClick = { preview = false }) { Text(tr("close")) } },
        )
    }
}

@Composable
fun HabitHistory(item: OrgHeading) {
    val context = LocalContext.current
    val today = LocalToday.current.date
    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
        Text(tr("habit_history"), style = MaterialTheme.typography.labelMedium)
        for (week in 0..3) Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            for (day in 0..6) {
                val date = today.minusDays((27 - week * 7 - day).toLong())
                Surface(
                    color =
                        if (date in item.completedDays) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                    modifier =
                        Modifier.weight(1f).heightIn(min = 32.dp).semantics {
                            contentDescription =
                                "$date: " +
                                    context.label(
                                        if (date in item.completedDays) "completed" else "none"
                                    )
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (date in item.completedDays) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun agendaPerspectives(snapshot: WorkspaceSnapshot): List<String> =
    listOf("overview", "overdue", "unscheduled") +
        snapshot.configuration.workflow.sequences.flatMap { it.process }.distinct() +
        "completed"

@Composable
fun AgendaPerspectiveMenu(
    snapshot: WorkspaceSnapshot,
    perspective: String,
    selectPerspective: (String) -> Unit,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val perspectives = agendaPerspectives(snapshot)
    val displayedPerspective = perspective.takeIf { it in perspectives } ?: "overview"
    fun display(value: String) =
        when (value) {
            "overview",
            "overdue",
            "unscheduled",
            "completed" -> context.label(value)
            else -> snapshot.configuration.workflow.style(value).label.ifEmpty { value }
        }
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
            contentPadding = PaddingValues(start = 0.dp, end = 8.dp),
            colors =
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            modifier =
                Modifier.testTag("agenda.perspective").semantics {
                    contentDescription =
                        context.label("agenda") + ": " + display(displayedPerspective)
                },
        ) {
            Text(
                display(displayedPerspective),
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                null,
                Modifier.padding(start = 4.dp),
                tint = if (enabled) mutedControlColor() else LocalContentColor.current,
            )
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            perspectives.forEach { value ->
                DropdownMenuItem(
                    text = { Text(display(value)) },
                    onClick = {
                        expanded = false
                        selectPerspective(value)
                    },
                    trailingIcon = {
                        if (value == displayedPerspective) Icon(Icons.Default.Check, null)
                    },
                )
            }
        }
    }
}

@Composable
fun AgendaScreen(
    snapshot: WorkspaceSnapshot,
    open: (OrgHeading) -> Unit,
    toggle: (OrgHeading) -> Unit,
    archive: (OrgHeading) -> Unit,
    refile: (OrgHeading) -> Unit,
    perspective: String,
    selectPerspective: (String) -> Unit,
    showFile: (OrgHeading) -> Unit,
    reschedule: (OrgHeading) -> Unit,
) {
    val context = LocalContext.current
    val currentDay = LocalToday.current
    val today = currentDay.date
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val openStates = snapshot.configuration.workflow.sequences.flatMap { it.process }.distinct()
    val perspectives = agendaPerspectives(snapshot)
    val displayedPerspective = perspective.takeIf { it in perspectives } ?: "overview"
    val perspectiveStates = rememberSaveableStateHolder()
    LaunchedEffect(perspective, perspectives) {
        if (perspective !in perspectives) selectPerspective("overview")
    }
    val openItems = snapshot.items.filter { it.isTask && !it.done }
    val unscheduled = openItems.filter { it.agendaStamp == null }.sortedWith(Agenda.priorityOrder)
    val overdue = openItems.filter { Agenda.overdue(it, today) }
    val canonical = remember(snapshot.revision) { snapshot.items.associateBy { it.id } }
    val groups =
        remember(snapshot.revision, displayedPerspective, currentDay, locale) {
            when (displayedPerspective) {
                "overview" ->
                    buildList<Pair<String, List<OrgHeading>>> {
                        if (overdue.isNotEmpty())
                            add(
                                context.label("overdue") to
                                    overdue.sortedBy { it.agendaStamp?.date }
                            )
                        (0L..6L).forEach { delta ->
                            val day = today.plusDays(delta)
                            val items = Agenda.on(snapshot.items, day)
                            if (items.isNotEmpty())
                                add(
                                    day.format(
                                        java.time.format.DateTimeFormatter.ofPattern(
                                            "EEE, MMM d",
                                            locale,
                                        )
                                    ) to items
                                )
                        }
                        val deadlines =
                            openItems
                                .filter {
                                    it.deadline?.date?.toLocalDate()?.let { date ->
                                        date > today &&
                                            date <=
                                                today.plusDays(
                                                    snapshot.configuration.reminders
                                                        .deadlineWarningDays
                                                        .toLong()
                                                )
                                    } == true
                                }
                                .sortedBy { it.deadline?.date }
                        if (deadlines.isNotEmpty())
                            add(context.label("upcoming_deadlines") to deadlines)
                        openStates.forEach { state ->
                            val items = unscheduled.filter { it.state == state }
                            if (items.isNotEmpty())
                                add(
                                    snapshot.configuration.workflow.style(state).label.ifEmpty {
                                        state
                                    } to items
                                )
                        }
                    }
                "overdue" ->
                    overdue
                        .groupBy { it.agendaStamp!!.date.toLocalDate() }
                        .toSortedMap()
                        .map { it.key.toString() to it.value }
                "unscheduled" -> unscheduled.groupBy { it.path }.toSortedMap().toList()
                "completed" ->
                    listOf(
                        context.label("completed") to
                            snapshot.items.filter { it.done }.sortedWith(Agenda.priorityOrder)
                    )
                else ->
                    listOf(
                        snapshot.configuration.workflow.style(displayedPerspective).label.ifEmpty {
                            displayedPerspective
                        } to
                            openItems
                                .filter { it.state == displayedPerspective }
                                .sortedWith(Agenda.priorityOrder)
                    )
            }
        }
    Column(Modifier.fillMaxSize()) {
        if (groups.all { it.second.isEmpty() }) EmptyState("no_tasks")
        else
            perspectiveStates.SaveableStateProvider(displayedPerspective) {
                LazyColumn(
                    modifier = Modifier.testTag("agenda.list.$displayedPerspective"),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {

                    // The date scope is still shown when Overdue is the only populated group.
                    for (index in 0..groups.size) {
                        if (
                            displayedPerspective == "overview" &&
                                index == if (overdue.isEmpty()) 0 else 1
                        ) {
                            item(key = "date-scope") {
                                Text(
                                    tr("next_seven_days"),
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        .semantics { heading() },
                                    style =
                                        MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                                )
                            }
                            if (
                                (0L..6L).all {
                                    Agenda.on(snapshot.items, today.plusDays(it)).isEmpty()
                                }
                            )
                                item(key = "empty-date-scope") {
                                    Text(
                                        tr("nothing_next_seven_days"),
                                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                        }
                        if (index == groups.size) continue
                        val (title, rows) = groups[index]
                        stickyHeader(key = "header:$index") { ListSectionHeader(title, rows.size) }
                        items(rows, key = { "$index:${it.id}" }) { item ->
                            val original = canonical.getValue(item.id)
                            TaskRow(
                                item,
                                { open(original) },
                                { toggle(original) },
                                { archive(original) },
                                { refile(original) },
                                onShowFile = { showFile(original) },
                                onReschedule = { reschedule(original) },
                            )
                            HorizontalDivider(
                                Modifier.padding(start = 72.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
    }
}
