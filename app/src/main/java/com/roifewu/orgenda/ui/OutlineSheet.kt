@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.roifewu.orgenda.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.*
import com.roifewu.orgenda.core.*
import java.time.LocalDate

private val OutlineFilterSaver = listSaver<OutlineFilter, String>(
    save = { listOf(it.scheduledFrom?.toString().orEmpty(), it.scheduledTo?.toString().orEmpty(), it.deadlineFrom?.toString().orEmpty(), it.deadlineTo?.toString().orEmpty()) + it.states },
    restore = { values ->
        fun day(index: Int) = values[index].takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
        OutlineFilter(values.drop(4).toSet(), day(0), day(1), day(2), day(3))
    }
)
private val OutlineExpandedSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

@Composable fun OutlineSheet(model: OrgendaViewModel, editor: EditorState, close: () -> Unit) {
    val document = editor.document ?: return
    ModalPresence()
    var filter by rememberSaveable(editor.session, stateSaver = OutlineFilterSaver) { mutableStateOf(OutlineFilter()) }
    var showFilter by rememberSaveable(editor.session) { mutableStateOf(false) }
    var expanded by rememberSaveable(editor.session, stateSaver = OutlineExpandedSaver) { mutableStateOf(emptySet<String>()) }
    var move by remember { mutableStateOf<Pair<OrgHeading, OrgHeading>?>(null) }
    val keys = remember(document) { Outline.keys(document) }
    var handledMove by rememberSaveable(editor.session) { mutableStateOf(editor.headingMove?.text?.sha256()) }
    LaunchedEffect(document, editor.headingMove) {
        val moved = editor.headingMove
        val revision = moved?.text?.sha256()
        if (moved != null && moved.text == document.text && revision != handledMove) {
            expanded = expanded.mapNotNull(editor.headingMoveKeys::get).toSet() +
                document.headings.filter { editor.jumpByte > it.start && editor.jumpByte < it.subtreeEnd }.map { keys.getValue(it.start) }
            handledMove = revision
        }
    }
    val filtered = remember(document, filter, expanded) { filter.apply(document.headings).filter { heading -> filter.active || document.headings.none { parent -> heading.start > parent.start && heading.start < parent.subtreeEnd && keys[parent.start] !in expanded } } }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("outline"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { showFilter = true }, modifier = Modifier.testTag("outline.filter")) { Icon(Icons.Default.FilterList, tr("filter")) }
            TextButton(onClick = close) { Text(tr("close")) }
        }
        if (filter.active) Row(Modifier.padding(horizontal = 16.dp)) { Text("${document.headings.count(filter::matches)} " + tr("matched_headings"), Modifier.weight(1f)); TextButton(onClick = { filter = OutlineFilter() }) { Text(tr("clear")) } }
        if (filtered.isEmpty()) Text(tr("no_results"), Modifier.padding(24.dp))
        LazyColumn(Modifier.fillMaxHeight(0.8f)) {
            items(filtered, key = { it.start }) { heading ->
                val children = document.headings.any { it.start > heading.start && it.start < heading.subtreeEnd }
                Row(Modifier.fillMaxWidth().padding(start = ((heading.level - 1).coerceAtMost(8) * 12).dp).orgDrop("heading") { payload -> (payload as? OrgHeading)?.let { move = it to heading } }, verticalAlignment = Alignment.CenterVertically) {
                    if (children) IconButton(enabled = !filter.active, onClick = { val key = keys.getValue(heading.start); expanded = if (key in expanded) expanded - key else expanded + key }, modifier = Modifier.testTag("outline.toggle.${heading.start}")) { Icon(if (filter.active || keys[heading.start] in expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, tr(if (filter.active || keys[heading.start] in expanded) "collapse" else "expand") + " " + heading.title) } else Spacer(Modifier.width(48.dp))
                    TextButton(onClick = { if (model.editor.value?.text == document.text) { model.jump(heading.start); close() } }, modifier = Modifier.weight(1f).testTag("outline.heading.${heading.start}")) { Column(Modifier.fillMaxWidth()) { Text(heading.title); if (heading.state.isNotEmpty()) Text(heading.state, color = workflowColor(heading), style = MaterialTheme.typography.labelSmall) } }
                    HeadingMoveMenu(model, document, heading)
                }
            }
        }
    }
    if (showFilter) { ModalPresence(); ModalBottomSheet(onDismissRequest = { showFilter = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(tr("filter"), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { document.workflow.tokens.forEach { token -> FilterChip(token in filter.states, { filter = filter.copy(states = if (token in filter.states) filter.states - token else filter.states + token) }, label = { Text(token) }, modifier = Modifier.testTag("outline.filter.state.$token")) } }
            fun value(day: LocalDate?) = PlanningValue(day != null, (day ?: LocalDate.now()).atStartOfDay(), false, "")
            Text(tr("scheduled")); PlanningField("start", value(filter.scheduledFrom), showRepeat = false, showTimeToggle = false) { filter = filter.copy(scheduledFrom = it.date.toLocalDate().takeIf { _ -> it.enabled }) }
            PlanningField("end", value(filter.scheduledTo), showRepeat = false, showTimeToggle = false) { filter = filter.copy(scheduledTo = it.date.toLocalDate().takeIf { _ -> it.enabled }) }
            Text(tr("deadline")); PlanningField("start", value(filter.deadlineFrom), showRepeat = false, showTimeToggle = false) { filter = filter.copy(deadlineFrom = it.date.toLocalDate().takeIf { _ -> it.enabled }) }
            PlanningField("end", value(filter.deadlineTo), showRepeat = false, showTimeToggle = false) { filter = filter.copy(deadlineTo = it.date.toLocalDate().takeIf { _ -> it.enabled }) }
            Row { TextButton(onClick = { filter = OutlineFilter() }) { Text(tr("reset")) }; TextButton(onClick = { showFilter = false }, modifier = Modifier.testTag("outline.filter.close")) { Text(tr("close")) } }
        }
    }
    }
    move?.let { (from, target) -> HeadingDropDialog(from, target, { move = null }) { placement -> model.moveHeading(from, target, placement); move = null } }
}

@Composable fun HeadingMoveMenu(model: OrgendaViewModel, document: OrgDocument, item: OrgHeading, setState: ((String) -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    val parent = document.headings.lastOrNull { it.start < item.start && it.subtreeEnd > item.start && it.level < item.level }
    fun parent(start: Int) = document.headings.lastOrNull { it.start < start && it.subtreeEnd > start && it.level < item.level }?.start
    val siblings = document.headings.filter { it.level == item.level && parent(it.start) == parent?.start }
    val previous = siblings.lastOrNull { it.start < item.start }; val next = siblings.firstOrNull { it.start > item.start }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.orgDrag("heading", item)) { Icon(Icons.Default.DragIndicator, tr(if (setState != null) "actions" else "move")) }
        DropdownMenu(menu, { menu = false }) {
            if (setState != null) {
                item.workflow.tokens.forEach { state -> DropdownMenuItem(text = { Text(item.workflow.style(state).label.ifEmpty { state }) }, leadingIcon = { WorkflowIcon(item.workflow.style(state).icon, workflowColor(item.workflow.style(state).color)) }, onClick = { menu = false; setState(state) }) }
                HorizontalDivider()
            }
            listOf(Triple("up", previous, HeadingPlacement.BEFORE), Triple("down", next, HeadingPlacement.AFTER), Triple("indent", previous, HeadingPlacement.CHILD), Triple("outdent", parent, HeadingPlacement.AFTER)).forEach { (label, target, placement) ->
                DropdownMenuItem(text = { Text(tr(label)) }, enabled = target != null, onClick = { menu = false; target?.let { model.moveHeading(item, it, placement) } })
            }
        }
    }
}

@Composable fun HeadingDropDialog(item: OrgHeading, target: OrgHeading, close: () -> Unit, move: (HeadingPlacement) -> Unit) {
    ModalPresence()
    AlertDialog(onDismissRequest = close, title = { Text(tr("move") + ": " + item.title) }, text = { Column { Text(target.title); HeadingPlacement.entries.forEach { placement -> TextButton(onClick = { move(placement) }) { Text(tr("placement_${placement.name.lowercase()}")) } } } }, confirmButton = { TextButton(onClick = close) { Text(tr("cancel")) } })
}
