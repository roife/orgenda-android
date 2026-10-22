@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.roifewu.orgenda.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.OrgHeading
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable fun OrgendaApp(model: OrgendaViewModel, intents: StateFlow<Intent?>, consumed: () -> Unit) {
    val workspace by model.workspace.collectAsStateWithLifecycle()
    val fileUndo by model.fileUndo.collectAsStateWithLifecycle()
    val undo by model.gestureUndo.collectAsStateWithLifecycle()
    val editor by model.editor.collectAsStateWithLifecycle()
    val preferences by model.settings.collectAsStateWithLifecycle()
    val incoming by intents.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("agenda") }
    var perspective by rememberSaveable { mutableStateOf("overview") }
    var settingsSection by rememberSaveable { mutableStateOf<String?>(null) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var capture by rememberSaveable { mutableStateOf(false) }
    var captureDate by rememberSaveable(stateSaver = Saver<LocalDate?, String>(save = { it?.toString() }, restore = { LocalDate.parse(it) })) { mutableStateOf<LocalDate?>(null) }
    var filesFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var searchFocusRequest by rememberSaveable { mutableIntStateOf(0) }
    var calendarNavigation by rememberSaveable { mutableIntStateOf(0) }
    val modalCount = remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    var starting by remember { mutableStateOf(true) }
    var startupError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model) {
        try { model.app.ready.await() } catch (e: Exception) { startupError = e.message }
        finally { starting = false }
    }
    fun startCapture(day: LocalDate? = null) { captureDate = day; capture = true }
    fun navigate(destination: String) {
        if (editor == null) page = destination
        else coroutineScope.launch {
            model.closeEditor().join()
            if (model.editor.value == null) page = destination
        }
    }
    var journal by rememberSaveable { mutableStateOf(false) }
    var editing by rememberHeadingSelection(model)
    var completionNote by rememberHeadingSelection(model)
    var refile by rememberHeadingSelection(model)
    var scheduling by rememberHeadingSelection(model)
    var rescheduleNoteItem by rememberHeadingSelection(model)
    var rescheduleNoteDate by rememberSaveable { mutableStateOf("") }
    fun requestRescheduleNote(item: OrgHeading, day: LocalDate) { rescheduleNoteDate = day.toString(); rescheduleNoteItem = item }
    var leaveDraft by remember { mutableStateOf(false) }
    val pageStates = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(undo?.id) {
        undo?.let { change ->
            val result = snackbar.showSnackbar(context.label("updated"), context.label("undo"), withDismissAction = true, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) model.undoGesture(change) else model.clearGesture(change)
        }
    }
    LaunchedEffect(fileUndo?.id) {
        fileUndo?.let { change ->
            if (snackbar.showSnackbar(context.label("updated"), context.label("undo"), withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) model.undoFile(change) else model.clearFileUndo(change)
        }
    }
    LaunchedEffect(workspace.error) { workspace.error?.let { snackbar.showSnackbar(it); model.repository.clearError() } }
    LaunchedEffect(incoming, workspace.workspace?.id, modalCount.value, capture, journal, editing, completionNote, refile, scheduling, rescheduleNoteItem, leaveDraft, starting, editor == null) {
        val intent = incoming ?: return@LaunchedEffect
        if (intent.getStringExtra("action") !in listOf("capture", "today", "search", "files") && intent.getStringExtra("path") == null) {
            consumed()
            return@LaunchedEffect
        }
        if (starting || workspace.workspace == null || modalCount.value > 0 || capture || journal || editing != null || completionNote != null || refile != null || scheduling != null || rescheduleNoteItem != null || leaveDraft) return@LaunchedEffect
        if (model.editor.value != null) {
            model.closeEditor().join()
            if (model.editor.value != null) return@LaunchedEffect
        }
        val targetWorkspace = intent.getStringExtra("workspace")
        if (targetWorkspace != null && targetWorkspace != workspace.workspace?.id) {
            model.action { model.repository.switchWorkspace(targetWorkspace) }.join()
            if (model.workspace.value.workspace?.id != targetWorkspace) return@LaunchedEffect
        }
        intent.getStringExtra("path")?.let { path -> model.workspace.value.files.firstOrNull { it.path == path }?.let { model.open(it, intent.getIntExtra("byte", 0)) } }
        when (intent.getStringExtra("action")) {
            "capture" -> { page = "agenda"; perspective = "overview"; startCapture() }
            "today" -> { page = "calendar"; date = LocalDate.now().toString(); calendarNavigation++ }
            "search" -> { page = "search"; searchFocusRequest++ }
            "files" -> { page = "files"; filesFolder = "" }
        }
        consumed()
    }
    val toggle: (OrgHeading) -> Unit = { item ->
        val next = item.workflow.toggle(item.state)
        val repeat = item.agendaStamp?.repeater != null && !item.done && item.isTask
        val reopen = item.workflow.sequences.firstOrNull { item.state in it.process + it.terminal }?.reopen.orEmpty()
        val repeatNote = repeat && (item.workflow.style(next).log.leave == "note" || item.workflow.style(reopen).log.enter == "note" ||
            when (item.agendaStamp?.keyword) { "SCHEDULED" -> workspace.configuration.logging.reschedule == "note"; "DEADLINE" -> workspace.configuration.logging.redeadline == "note"; else -> false })
        if (repeatNote || item.workflow.style(next).log.enter == "note" || item.workflow.style(item.state).log.leave == "note" || item.workflow.terminal(next) && workspace.configuration.logging.done == "note") completionNote = item
        else model.toggle(item)
    }
    OrgendaTheme(preferences) {
        TodayProvider {
        CompositionLocalProvider(LocalModalCount provides modalCount) {
        BackHandler(editor != null) { if (editor?.error != null) leaveDraft = true else model.closeEditor() }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 600.dp
            val twoPane = maxWidth >= 840.dp
            val destinations = listOf("agenda" to Icons.Default.Checklist, "calendar" to Icons.Default.CalendarMonth, "files" to Icons.Default.Folder, "search" to Icons.Default.Search, "settings" to Icons.Default.Settings)
            Scaffold(
                topBar = {
                    if (editor == null || expanded) TopAppBar(title = { Text(if (page == "agenda") "Orgenda" else tr(page)) }, actions = {
                        if (workspace.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        IconButton(onClick = { model.sync() }, enabled = !starting && workspace.workspace != null && !workspace.syncing) { Icon(Icons.Default.Sync, tr("sync")) }
                    })
                },
                bottomBar = {
                    if (!expanded && editor == null) NavigationBar {
                        destinations.forEach { (key, icon) -> NavigationBarItem(enabled = !starting, selected = page == key, onClick = { navigate(key) }, icon = { Icon(icon, tr(key)) }, label = { Text(tr(key)) }) }
                    }
                },
                floatingActionButton = {
                    if (!starting && workspace.workspace != null && editor == null && page in listOf("agenda", "calendar")) FloatingActionButton(onClick = { startCapture(if (page == "calendar") LocalDate.parse(date) else null) }) { Icon(Icons.Default.Add, tr("new_task")) }
                }, snackbarHost = { SnackbarHost(snackbar) }
            ) { insets ->
                Row(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                    if (expanded) NavigationRail {
                        destinations.forEach { (key, icon) -> NavigationRailItem(enabled = !starting, selected = page == key, onClick = { navigate(key) }, icon = { Icon(icon, tr(key)) }, label = { Text(tr(key)) }) }
                    }
                    if (starting) Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
                    else if (workspace.workspace == null && page != "settings") Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(tr("workspace_unavailable"), style = MaterialTheme.typography.titleLarge)
                        startupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = {
                            starting = true
                            coroutineScope.launch {
                                try { withContext(Dispatchers.IO) { model.repository.start() }; startupError = null }
                                catch (e: Exception) { startupError = e.message }
                                finally { starting = false }
                            }
                        }) { Text(tr("retry")) }
                        TextButton(onClick = { settingsSection = "workspace"; page = "settings" }) { Text(tr("local_folder")) }
                    }
                    else if (editor != null && (!twoPane || page != "files")) {
                        DocumentScreen(model, editor!!, { editing = it }, { if (editor?.error != null) leaveDraft = true else model.closeEditor() }, toggle)
                    } else pageStates.SaveableStateProvider(page) { when (page) {
                        "agenda" -> AgendaScreen(workspace, { editing = it }, toggle, { model.archive(it) }, { refile = it }, perspective, { perspective = it }, { item -> workspace.files.firstOrNull { it.path == item.path }?.let { model.open(it, item.start) } }, { scheduling = it })
                        "calendar" -> key(calendarNavigation) { CalendarScreen(workspace, LocalDate.parse(date), { date = it.toString() }, { editing = it }, toggle, { journal = true }, { path, byte -> workspace.files.firstOrNull { it.path == path }?.let { model.open(it, byte) } }, { startCapture(LocalDate.parse(date)) }, { perspective = "overdue"; page = "agenda" }) { item, day ->
                            val rule = workspace.configuration.logging.reschedule
                            if (rule == "note") requestRescheduleNote(item, day) else model.reschedule(item, day)
                        }
                        }
                        "files" -> {
                            Box(Modifier.then(if (twoPane) Modifier.width(320.dp) else Modifier.weight(1f))) { FilesScreen(model, workspace, filesFolder) { filesFolder = null } }
                            if (twoPane) { VerticalDivider(); Box(Modifier.weight(1f)) { if (editor != null) DocumentScreen(model, editor!!, { editing = it }, { model.closeEditor() }, toggle) else EmptyState("select_file") } }
                        }
                        "search" -> SearchScreen(model, workspace, searchFocusRequest, { folder -> filesFolder = folder; page = "files" }) { section -> settingsSection = section; page = "settings" }
                        "settings" -> SettingsScreen(model, workspace, settingsSection) { settingsSection = null }
                    } }
                }
            }
        }
        if (capture) { if (captureDate != null) NewScheduledItemSheet(model, captureDate!!) { capture = false } else CaptureSheet(model) { capture = false } }
        if (journal) JournalSheet(LocalDate.parse(date), { journal = false }) { title, body -> model.newJournal(LocalDate.parse(date), title, body) }
        editing?.let { item -> ItemEditorSheet(model, item) { editing = null } }
        completionNote?.let { item -> TextPrompt("completed", label = "note", multiline = true, onDismiss = { completionNote = null }) { note -> completionNote = null; model.toggle(item, note) } }
        refile?.let { item -> RefileSheet(model, item) { refile = null } }
        scheduling?.let { item -> RescheduleSheet(item, { scheduling = null }) { day ->
            scheduling = null
            if (workspace.configuration.logging.reschedule == "note") requestRescheduleNote(item, day) else model.reschedule(item, day)
        } }
        rescheduleNoteItem?.let { item -> TextPrompt("scheduled", label = "note", multiline = true, onDismiss = { rescheduleNoteItem = null }) { note -> model.reschedule(item, LocalDate.parse(rescheduleNoteDate), note); rescheduleNoteItem = null } }
        if (leaveDraft) AlertDialog(onDismissRequest = { leaveDraft = false }, title = { Text(tr("unsaved")) }, text = { Text(tr("save_failed")) },
            confirmButton = { TextButton(onClick = { leaveDraft = false }) { Text(tr("continue_editing")) } },
            dismissButton = { TextButton(onClick = { model.discardEditor(); leaveDraft = false }) { Text(tr("discard")) } })
        }
        }
    }
}
