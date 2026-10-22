@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.roifewu.orgenda.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.*
import kotlinx.coroutines.*
import java.time.*

data class PlanningValue(val enabled: Boolean, val date: LocalDateTime, val time: Boolean, val repeater: String)
internal val LocalDateTimeSaver = Saver<LocalDateTime, String>(save = { it.toString() }, restore = LocalDateTime::parse)
internal val PlanningValueSaver = listSaver<PlanningValue, Any>(
    save = { listOf(it.enabled, it.date.toString(), it.time, it.repeater) },
    restore = { PlanningValue(it[0] as Boolean, LocalDateTime.parse(it[1] as String), it[2] as Boolean, it[3] as String) }
)
private val RepeaterSaver = Saver<OrgRepeater, String>(save = { it.token }, restore = OrgRepeater::parse)
private val ConfigurationSaver = Saver<WorkspaceConfig, String>(save = { ConfigurationCodec.encode(it) }, restore = { ConfigurationCodec.decode(it) })
private fun OrgStamp?.draft() = PlanningValue(this != null, this?.date ?: LocalDateTime.now().withSecond(0).withNano(0), this?.hasTime ?: false, this?.repeater?.token ?: "")

@Composable fun PlanningField(label: String, value: PlanningValue, allowDisable: Boolean = true, showRepeat: Boolean = true, showTimeToggle: Boolean = true, change: (PlanningValue) -> Unit) {
    var datePicker by rememberSaveable { mutableStateOf(false) }; var timePicker by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(tr(label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); if (allowDisable) Switch(value.enabled, { change(value.copy(enabled = it)) }) }
        if (value.enabled) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (label == "scheduled") { TextButton(onClick = { change(value.copy(date = LocalDate.now().atTime(value.date.toLocalTime()))) }) { Text(tr("today")) }; TextButton(onClick = { change(value.copy(date = LocalDate.now().plusDays(1).atTime(value.date.toLocalTime()))) }) { Text(tr("tomorrow")) } }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { datePicker = true }) { Text(value.date.toLocalDate().toString()) }
                if (showTimeToggle) FilterChip(value.time, { change(value.copy(time = !value.time, date = if (!value.time && value.date.toLocalTime() == LocalTime.MIDNIGHT) value.date.toLocalDate().atTime(LocalTime.now().withSecond(0).withNano(0)) else value.date)) }, label = { Text(tr("time")) })
                if (value.time) OutlinedButton(onClick = { timePicker = true }) { Text(value.date.toLocalTime().toString()) }
            }
            if (showRepeat) RecurrenceField(value.repeater) { change(value.copy(repeater = it)) }
        }
    }
    if (datePicker) {
        ModalPresence()
        val state = rememberDatePickerState(value.date.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { datePicker = false }, confirmButton = { TextButton(onClick = {
            state.selectedDateMillis?.let { change(value.copy(date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().atTime(value.date.toLocalTime()))) }; datePicker = false
        }) { Text(tr("save")) } }, dismissButton = { TextButton(onClick = { datePicker = false }) { Text(tr("cancel")) } }) { DatePicker(state) }
    }
    if (timePicker) {
        ModalPresence()
        val state = rememberTimePickerState(value.date.hour, value.date.minute, android.text.format.DateFormat.is24HourFormat(context))
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        var keyboard by rememberSaveable(configuration.fontScale) { mutableStateOf(configuration.fontScale >= 1.5f) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { timePicker = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(16.dp).widthIn(max = 380.dp).heightIn(max = (configuration.screenHeightDp - 64).coerceAtLeast(220).dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr("time"), style = MaterialTheme.typography.titleLarge)
                    if (keyboard) TimeInput(state) else TimePicker(state)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { keyboard = !keyboard }) { Icon(if (keyboard) Icons.Default.Schedule else Icons.Default.Keyboard, tr("time_input_mode")) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { timePicker = false }) { Text(tr("cancel")) }
                        TextButton(onClick = { change(value.copy(date = value.date.withHour(state.hour).withMinute(state.minute), time = true)); timePicker = false }) { Text(tr("save")) }
                    }
                }
            }
        }
    }
}

@Composable fun ItemEditorSheet(model: OrgendaViewModel, item: OrgHeading, proposedState: String? = null, newDocument: OrgDocument? = null, close: () -> Unit) {
    var current by remember(item.id, item.sourceHash) { mutableStateOf(item) }
    key(current.id, current.sourceHash) { ItemEditorForm(model, current, proposedState.takeIf { current == item }, newDocument, { current = it }, close) }
}

@Composable private fun ItemEditorForm(model: OrgendaViewModel, item: OrgHeading, proposedState: String?, newDocument: OrgDocument?, reload: (OrgHeading) -> Unit, close: () -> Unit) {
    ModalPresence()
    val workspace by model.workspace.collectAsStateWithLifecycle()
    val initialWorkspace by rememberSaveable { mutableStateOf(model.workspace.value.workspace?.id) }
    val initialConfig = rememberSaveable(saver = ConfigurationSaver) { model.workspace.value.configuration }
    val initialDocument = remember { newDocument ?: model.workspace.value.documents[item.path] }
    var title by rememberSaveable { mutableStateOf(item.title) }; var state by rememberSaveable { mutableStateOf(proposedState ?: item.state) }
    var priority by rememberSaveable { mutableStateOf(item.priority) }; var tags by rememberSaveable { mutableStateOf(item.tags.joinToString(" ")) }
    val initialScheduled = rememberSaveable(saver = PlanningValueSaver) { item.scheduled.draft() }; val initialDeadline = rememberSaveable(saver = PlanningValueSaver) { item.deadline.draft() }; val initialEvent = rememberSaveable(saver = PlanningValueSaver) { item.event.draft() }
    var scheduled by rememberSaveable(stateSaver = PlanningValueSaver) { mutableStateOf(initialScheduled) }; var deadline by rememberSaveable(stateSaver = PlanningValueSaver) { mutableStateOf(initialDeadline) }
    val initialNotes = remember { item.body }
    var kind by rememberSaveable { mutableStateOf(item.kind) }
    var event by rememberSaveable(stateSaver = PlanningValueSaver) { mutableStateOf(initialEvent) }
    var duration by rememberSaveable { mutableIntStateOf((item.agendaStamp?.durationMinutes ?: 30).toInt()) }
    var warning by rememberSaveable { mutableStateOf(item.properties["APPT_WARNTIME"] ?: "") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var sourcePreview by remember { mutableStateOf(false) }
    var latestVersion by remember { mutableStateOf<Pair<OrgHeading, String>?>(null) }
    var refile by remember { mutableStateOf(false) }
    var archive by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var notes by rememberSaveable { mutableStateOf(initialNotes) }; var logNote by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }; var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val titleFocus = remember { FocusRequester() }
    val dirty = title != item.title || state != item.state || priority != item.priority || tags != item.tags.joinToString(" ") || notes != initialNotes || logNote.isNotEmpty() || scheduled != initialScheduled || deadline != initialDeadline || event != initialEvent || kind != item.kind || warning != (item.properties["APPT_WARNTIME"] ?: "") || duration != (item.agendaStamp?.durationMinutes ?: 30).toInt()
    val requiresNote = state != item.state && (item.workflow.style(state).log.enter == "note" || item.workflow.style(item.state).log.leave == "note" || item.workflow.terminal(state) && initialConfig.logging.done == "note") || scheduled != initialScheduled && initialConfig.logging.reschedule == "note" || deadline != initialDeadline && initialConfig.logging.redeadline == "note"
    val validWarning = warning == (item.properties["APPT_WARNTIME"] ?: "") || warning.toIntOrNull()?.let { it in 0..1440 } == true
    fun update(doc: OrgDocument, original: OrgHeading, preview: Boolean = false): String {
        val writeConfig = if (preview) initialConfig.copy(logging = Logging()) else initialConfig
        val engine = model.repository.engine
        var text = engine.changeItem(doc, original, title = title.lines().joinToString(" ").trim(), priority = priority, tags = tags.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.let { values -> if (kind != item.kind) values.filterNot { it in listOf("event", "project", "habit", "note") } + if (kind != "task") listOf(kind) else emptyList() else values })
        var parsed = engine.parse(item.path, text, initialConfig)
        if (scheduled != initialScheduled) {
            text = engine.setPlanning(parsed, parsed.heading(original.start), "SCHEDULED", scheduled.date.takeIf { scheduled.enabled }, scheduled.time, scheduled.repeater, writeConfig, logNote)
            parsed = engine.parse(item.path, text, initialConfig)
        }
        if (deadline != initialDeadline) {
            text = engine.setPlanning(parsed, parsed.heading(original.start), "DEADLINE", deadline.date.takeIf { deadline.enabled }, deadline.time, deadline.repeater, writeConfig, logNote)
            parsed = engine.parse(item.path, text, initialConfig)
        }
        if (event != initialEvent || item.event != null && duration != item.event!!.durationMinutes.toInt()) {
            text = engine.setEvent(parsed, parsed.heading(original.start), event.date.takeIf { event.enabled }, event.time, event.repeater, duration)
            parsed = engine.parse(item.path, text, initialConfig)
        }
        if (scheduled.enabled && scheduled.time && duration != (item.scheduled?.durationMinutes ?: 30).toInt()) {
            val stamp = parsed.heading(original.start).scheduled!!
            val end = scheduled.date.plusMinutes(duration.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            val raw = stamp.raw.replace(Regex("([0-9]{2}:[0-9]{2})(?:-[0-9]{2}:[0-9]{2})?")) { it.groupValues[1] + "-$end" }
            text = parsed.syntax.source.apply(listOf(SourcePatch(stamp.start, stamp.end, raw))); parsed = engine.parse(item.path, text, initialConfig)
        }
        if (warning.isNotEmpty() && warning != item.properties["APPT_WARNTIME"]) {
            require(warning.toIntOrNull()?.let { it in 0..1440 } == true)
            text = engine.setProperty(parsed, parsed.heading(original.start), "APPT_WARNTIME", warning); parsed = engine.parse(item.path, text, initialConfig)
        }
        if (notes != initialNotes) { text = engine.replaceBody(parsed, parsed.heading(original.start), notes); parsed = engine.parse(item.path, text, initialConfig) }
        if (state != item.state) text = if (preview) engine.changeItem(parsed, parsed.heading(original.start), state = state) else engine.transition(parsed, parsed.heading(original.start), state, initialConfig, note = logNote)
        return text
    }
    fun draftSource(): String {
        val doc = initialDocument ?: error("Document unavailable")
        val original = model.repository.engine.resolve(doc, item)
        val text = update(doc, original, preview = true)
        val parsed = model.repository.engine.parse(item.path, text, initialConfig)
        val heading = parsed.heading(original.start)
        return parsed.syntax.source.slice(heading.start, heading.bodyEnd) + if (logNote.isNotBlank()) "\n# Draft status note:\n" + logNote.lines().joinToString("\n") { "# $it" } else ""
    }
    var renderedDraft by remember { mutableStateOf("") }
    fun dismiss() { if (!saving) { if (dirty) confirmDiscard = true else close() } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { next ->
        if (next == SheetValue.Hidden && (saving || dirty)) { if (!saving) confirmDiscard = true; false } else true
    })
    ModalBottomSheet(onDismissRequest = ::dismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(if (newDocument != null) "new_task" else "edit"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = ::dismiss, enabled = !saving) { Text(tr("cancel")) }
                Button(enabled = !saving && title.isNotBlank() && validWarning && (!requiresNote || logNote.isNotBlank()) && listOf(scheduled, deadline, event).all { !it.enabled || it.repeater.isEmpty() || OrgRepeater.parse(it.repeater) != null } && (duration == (item.agendaStamp?.durationMinutes ?: 30).toInt() || duration in 5..480), onClick = {
                    saving = true; error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                require(model.workspace.value.workspace?.id == initialWorkspace && model.workspace.value.configuration == initialConfig) { "Workspace or configuration changed; reopen the task" }
                                if (newDocument != null) {
                                    val snapshot = model.workspace.value
                                    val target = snapshot.files.firstOrNull { it.path == item.path && !it.trashed }
                                    val document = model.repository.engine.parse(item.path, target?.let { model.repository.text(it) } ?: "", initialConfig)
                                    val rendered = update(newDocument, item)
                                    val template = CaptureTemplate(target = CaptureTarget(path = item.path))
                                    val appended = CaptureEngine.insert(rendered, template, document)
                                    if (target == null) model.repository.create(item.path, appended) else model.repository.save(target.id, appended, target.contentBlob)
                                } else model.repository.mutate(item) { doc, original -> update(doc, original) }
                            }
                            com.roifewu.orgenda.platform.SyncWorker.enqueue(model.app, model.workspace.value.workspace?.id)
                            close()
                        } catch (e: Exception) { error = e.message; saving = false }
                    }
                }) { Text(tr("save")) }
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Field(title, { title = it }, "title", modifier = Modifier.focusRequester(titleFocus), singleLine = false)
            LaunchedEffect(Unit) { if (newDocument != null) titleFocus.requestFocus() }
            Choice(kind, listOf("task", "event", "project", "habit", "note"), "kind", { next ->
                kind = next
                if (next == "event" && !event.enabled) { event = (if (scheduled.enabled) scheduled else if (deadline.enabled) deadline else initialEvent).copy(enabled = true); scheduled = scheduled.copy(enabled = false) }
                if (next in listOf("event", "note") && !item.workflow.terminal(state)) state = ""
                else if (next in listOf("task", "project", "habit") && state.isEmpty()) state = item.workflow.sequences.first().initial
            }) { value -> model.app.label(if (value == "note") "note_kind" else value) }
            if (kind !in listOf("event", "note")) WorkflowStateField(item, state) { state = it }
            Choice(priority, (listOf("", "A", "B", "C") + priority).distinct(), "priority", { priority = it }) { it.ifEmpty { "—" } }
            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text(tr("tags")) }, singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), supportingText = { Text("work, focus") })
            if (event.enabled || kind == "event") PlanningField("event", event, allowDisable = kind != "event") { event = it; if (it.time && duration < 5) duration = 30 }
            if (!event.enabled) PlanningField("scheduled", scheduled) { scheduled = it; if (it.time && duration < 5) duration = 30 }
            PlanningField("deadline", deadline) { deadline = if (!deadline.enabled && it.enabled) it.copy(date = if (scheduled.enabled) scheduled.date else it.date) else it }
            if ((scheduled.enabled && scheduled.time) || (event.enabled && event.time)) NumberField(duration, "duration") { duration = it }
            if (item.properties.containsKey("APPT_WARNTIME")) Field(warning, { warning = it }, "advance", invalid = !validWarning, supporting = if (!validWarning) "0–1440" else null)
            Field(notes, { notes = it }, "notes", modifier = Modifier.heightIn(min = 120.dp), singleLine = false)
            if (requiresNote || logNote.isNotEmpty()) Field(logNote, { logNote = it }, "status_note", singleLine = false, supporting = tr("status_note_required"), invalid = requiresNote && logNote.isBlank())
            if (item.habit) HabitHistory(item)
            TextButton(onClick = { runCatching { draftSource() }.onSuccess { renderedDraft = it; sourcePreview = true }.onFailure { error = it.message } }) { Text(tr("preview")) }
            if (error != null) TextButton(onClick = { runCatching { draftSource() }.onSuccess { clipboard.setText(AnnotatedString(it)) }.onFailure { error = it.message } }) { Text(tr("copy_draft")) }
            if (error != null && newDocument == null) {
                val latest = workspace.documents[item.path]
                val current = latest?.headings?.filter { it.title == item.title && it.ancestors == item.ancestors }?.singleOrNull()
                if (latest != null && current != null) TextButton(onClick = { latestVersion = current to latest.syntax.source.slice(current.start, current.bodyEnd) }) { Text(tr("view_latest")) }
            }
            if (newDocument == null) Row { TextButton(onClick = { refile = true }, enabled = !dirty && !saving) { Text(tr("refile")) }; TextButton(onClick = { archive = true }, enabled = !dirty && !saving) { Text(tr("archive")) } }
            if (newDocument == null && dirty) Text(tr("save_before_move"), style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.text.selection.SelectionContainer { Text(item.path, style = MaterialTheme.typography.labelSmall) }
            if (newDocument == null) Text(tr("source_line") + ": " + (initialDocument?.syntax?.source?.slice(0, item.start)?.count { it == '\n' }?.plus(1) ?: 1), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(32.dp))
        }
    }
    if (confirmDiscard) DiscardDialog({ confirmDiscard = false }, close)
    if (refile) RefileSheet(model, item, onMoved = close) { refile = false }
    if (archive) AlertDialog(onDismissRequest = { archive = false }, title = { Text(tr("archive")) }, text = { Text(item.title) }, confirmButton = { TextButton(onClick = { archive = false; saving = true; scope.launch { try { withContext(Dispatchers.IO) { model.archiveNow(item) }; com.roifewu.orgenda.platform.SyncWorker.enqueue(model.app, initialWorkspace); close() } catch (e: Exception) { error = e.message; saving = false } } }) { Text(tr("archive")) } }, dismissButton = { TextButton(onClick = { archive = false }) { Text(tr("cancel")) } })
    latestVersion?.let { (latest, source) ->
        AlertDialog(onDismissRequest = { latestVersion = null }, title = { Text(tr("view_latest")) }, text = {
            androidx.compose.foundation.text.selection.SelectionContainer { Text(source, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace) }
        }, confirmButton = { TextButton(onClick = { runCatching { draftSource() }.onSuccess { clipboard.setText(AnnotatedString(it)); latestVersion = null; reload(latest) }.onFailure { error = it.message } }) { Text(tr("copy_reload")) } }, dismissButton = { TextButton(onClick = { latestVersion = null }) { Text(tr("continue_editing")) } })
    }
    if (sourcePreview) AlertDialog(onDismissRequest = { sourcePreview = false }, title = { Text(tr("preview")) }, text = {
        androidx.compose.foundation.text.selection.SelectionContainer { Text(renderedDraft, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace) }
    }, confirmButton = { TextButton(onClick = { sourcePreview = false }) { Text(tr("close")) } })

}

@Composable private fun WorkflowStateField(item: OrgHeading, value: String, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun label(token: String) = item.workflow.style(token).label.ifEmpty { token.ifEmpty { "—" } }
    ExposedDropdownMenuBox(expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(label(value), {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text(tr("state")) },
            leadingIcon = { if (value.isNotEmpty()) WorkflowIcon(item.copy(state = value)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            (listOf("") + item.workflow.tokens).forEach { token ->
                DropdownMenuItem(text = { Text(label(token)) }, leadingIcon = { if (token.isNotEmpty()) WorkflowIcon(item.copy(state = token)) }, onClick = { expanded = false; change(token) })
            }
        }
    }
}

@Composable fun RecurrenceField(value: String, change: (String) -> Unit) {
    val parsed = OrgRepeater.parse(value)
    var retained by rememberSaveable(stateSaver = RepeaterSaver) { mutableStateOf(parsed ?: OrgRepeater("+", 1, 'd')) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(parsed) { if (parsed != null) retained = parsed }
    val draft = parsed ?: retained
    fun update(next: OrgRepeater) {
        val maximum = Regex("/([0-9]+)([hdwmy])").matchEntire(next.maximum)
        val normalized = if (maximum != null && maximum.groupValues[2][0] == next.unit) {
            next.copy(maximum = "/${maxOf(maximum.groupValues[1].toLong(), next.interval)}${next.unit}")
        } else next
        retained = normalized
        change(normalized.token)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("repeat"), Modifier.weight(1f)); Switch(value.isNotEmpty(), { change(if (it) retained.token else "") }, modifier = Modifier.testTag("recurrence.enabled")) }
        if (value.isNotEmpty()) {
            Choice(draft.mode, listOf("+", "++", ".+"), "repeat_mode", { update(draft.copy(mode = it)) }) { context.label(when (it) { "++" -> "repeat_catch_up"; ".+" -> "repeat_restart"; else -> "repeat_cumulative" }) }
            Text(tr(when (draft.mode) { "++" -> "repeat_skip_missed"; ".+" -> "repeat_from_completion"; else -> "repeat_from_schedule" }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RepeatCountField(draft.interval, "interval") { raw ->
                val count = raw.toLongOrNull()?.takeIf { it > 0 }
                if (count != null) update(draft.copy(interval = count)) else change("${draft.mode}${raw}${draft.unit}${draft.maximum}")
            }
            Choice(draft.unit.toString(), listOf("h", "d", "w", "m", "y"), "unit", { update(draft.copy(unit = it[0])) }) { context.label("unit_$it") }
            val maximum = Regex("/([0-9]+)([hdwmy])").matchEntire(draft.maximum)
            Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("habit_maximum"), Modifier.weight(1f)); Switch(maximum != null, { update(draft.copy(maximum = if (it) "/${draft.interval}${draft.unit}" else "")) }) }
            if (maximum != null) {
                RepeatCountField(maximum.groupValues[1].toLong(), "habit_maximum", minimum = if (maximum.groupValues[2][0] == draft.unit) draft.interval else 1) { raw ->
                    val count = raw.toLongOrNull()?.takeIf { it > 0 }
                    if (count != null) update(draft.copy(maximum = "/$count${maximum.groupValues[2]}")) else change("${draft.mode}${draft.interval}${draft.unit}/$raw${maximum.groupValues[2]}")
                }
                Choice(maximum.groupValues[2], listOf("h", "d", "w", "m", "y"), "unit", { update(draft.copy(maximum = "/${maximum.groupValues[1]}$it")) }) { context.label("unit_$it") }
            }
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = if (parsed == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (parsed == null) Text(tr("repeat_rule_invalid"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }) { Text(tr("advanced")) }
            if (advanced) Field(value, change, "repeat", supporting = "+1d · ++1w · .+1m · +1d/3d", invalid = parsed == null)
        }
    }
}

@Composable private fun RepeatCountField(value: Long, label: String, minimum: Long = 1, change: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(value.toString()) }
    var lastAppliedValue by rememberSaveable { mutableLongStateOf(value) }
    LaunchedEffect(value) {
        if (lastAppliedValue != value) { text = value.toString(); lastAppliedValue = value }
    }
    val invalid = text.toLongOrNull()?.let { it > 0 } != true
    OutlinedTextField(text, { raw -> val adjusted = raw.toLongOrNull()?.takeIf { it > 0 && it < minimum }?.let { minimum.toString() } ?: raw; text = adjusted; change(adjusted) }, Modifier.fillMaxWidth().testTag("recurrence.$label"), label = { Text(tr(label)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = invalid,
        supportingText = if (invalid) { { Text(tr("enter_integer")) } } else null)
}

@Composable fun NewScheduledItemSheet(model: OrgendaViewModel, day: LocalDate, close: () -> Unit) {
    val document = remember { val config = model.workspace.value.configuration; model.repository.engine.parse(config.files.inbox, "* ${config.workflow.sequences.first().initial} \nSCHEDULED: ${orgTimestamp(day.atStartOfDay(), includesTime = false)}\n", config) }
    ItemEditorSheet(model, document.headings.single(), newDocument = document, close = close)
}
