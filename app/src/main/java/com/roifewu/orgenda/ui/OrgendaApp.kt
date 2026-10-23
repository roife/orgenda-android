@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roifewu.orgenda.ui

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roifewu.orgenda.EditorState
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.OrgHeading
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AppPage(val destination: String, val document: EditorState?) {
    val key: String
        get() = document?.let { "document:${it.session}" } ?: destination
}

@Composable
fun OrgendaApp(model: OrgendaViewModel, intents: StateFlow<Intent?>, consumed: () -> Unit) {
    val workspace by model.workspace.collectAsStateWithLifecycle()
    val fileUndo by model.fileUndo.collectAsStateWithLifecycle()
    val undo by model.gestureUndo.collectAsStateWithLifecycle()
    val editor by model.editor.collectAsStateWithLifecycle()
    val preferences by model.settings.collectAsStateWithLifecycle()
    val incoming by intents.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("agenda") }
    var perspective by rememberSaveable { mutableStateOf("overview") }
    var settingsSection by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    fun openSettings(section: String? = null) {
        settingsSection = section
        settingsOpen = true
    }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var capture by rememberSaveable { mutableStateOf(false) }
    var captureDate by
        rememberSaveable(
            stateSaver =
                Saver<LocalDate?, String>(
                    save = { it?.toString() },
                    restore = { LocalDate.parse(it) },
                )
        ) {
            mutableStateOf<LocalDate?>(null)
        }
    var filesFolder by rememberSaveable { mutableStateOf<String?>(null) }
    val filesState = workspace.workspace?.let { rememberFilesScreenState(it.id) }
    var searchFocusRequest by rememberSaveable { mutableIntStateOf(0) }
    var calendarNavigation by rememberSaveable { mutableIntStateOf(0) }
    val calendarState = rememberCalendarScreenState(LocalDate.parse(date), calendarNavigation)
    val modalCount = remember { mutableStateOf(0) }
    val swipeActions =
        remember(workspace.workspace?.id, page, editor?.session) { SwipeActionsCoordinator() }
    val coroutineScope = rememberCoroutineScope()
    var starting by remember { mutableStateOf(true) }
    var startupError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model) {
        try {
            model.app.ready.await()
        } catch (e: Exception) {
            startupError = e.message
        } finally {
            starting = false
        }
    }
    fun startCapture(day: LocalDate? = null) {
        captureDate = day
        capture = true
    }
    fun navigate(destination: String) {
        if (editor == null) page = destination
        else
            coroutineScope.launch {
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
    fun requestRescheduleNote(item: OrgHeading, day: LocalDate) {
        rescheduleNoteDate = day.toString()
        rescheduleNoteItem = item
    }
    var leaveDraft by remember { mutableStateOf(false) }
    val pageStates = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(undo?.id) {
        undo?.let { change ->
            val result =
                snackbar.showSnackbar(
                    context.label("updated"),
                    context.label("undo"),
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
            if (result == SnackbarResult.ActionPerformed) model.undoGesture(change)
            else model.clearGesture(change)
        }
    }
    LaunchedEffect(fileUndo?.id) {
        fileUndo?.let { change ->
            if (
                snackbar.showSnackbar(
                    context.label("updated"),
                    context.label("undo"),
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                ) == SnackbarResult.ActionPerformed
            )
                model.undoFile(change)
            else model.clearFileUndo(change)
        }
    }
    LaunchedEffect(workspace.error) {
        workspace.error?.let {
            snackbar.showSnackbar(it)
            model.repository.clearError()
        }
    }
    LaunchedEffect(
        incoming,
        workspace.workspace?.id,
        modalCount.value,
        settingsOpen,
        capture,
        journal,
        editing,
        completionNote,
        refile,
        scheduling,
        rescheduleNoteItem,
        leaveDraft,
        starting,
        editor == null,
    ) {
        val intent = incoming ?: return@LaunchedEffect
        if (
            intent.getStringExtra("action") !in listOf("capture", "today", "search", "files") &&
                intent.getStringExtra("path") == null
        ) {
            consumed()
            return@LaunchedEffect
        }
        if (
            starting ||
                workspace.workspace == null ||
                modalCount.value > 0 ||
                settingsOpen ||
                capture ||
                journal ||
                editing != null ||
                completionNote != null ||
                refile != null ||
                scheduling != null ||
                rescheduleNoteItem != null ||
                leaveDraft
        )
            return@LaunchedEffect
        if (model.editor.value != null) {
            model.closeEditor().join()
            if (model.editor.value != null) return@LaunchedEffect
        }
        val targetWorkspace = intent.getStringExtra("workspace")
        if (targetWorkspace != null && targetWorkspace != workspace.workspace?.id) {
            model.action { model.repository.switchWorkspace(targetWorkspace) }.join()
            if (model.workspace.value.workspace?.id != targetWorkspace) return@LaunchedEffect
        }
        intent.getStringExtra("path")?.let { path ->
            model.workspace.value.files
                .firstOrNull { it.path == path }
                ?.let { model.open(it, intent.getIntExtra("byte", 0)) }
        }
        when (intent.getStringExtra("action")) {
            "capture" -> {
                page = "agenda"
                perspective = "overview"
                startCapture()
            }
            "today" -> {
                page = "calendar"
                date = LocalDate.now().toString()
                calendarNavigation++
            }
            "search" -> {
                page = "search"
                searchFocusRequest++
            }
            "files" -> {
                page = "files"
                filesFolder = ""
            }
        }
        consumed()
    }
    val toggle: (OrgHeading) -> Unit = { item ->
        val next = item.workflow.toggle(item.state)
        val repeat = item.agendaStamp?.repeater != null && !item.done && item.isTask
        val reopen =
            item.workflow.sequences
                .firstOrNull { item.state in it.process + it.terminal }
                ?.reopen
                .orEmpty()
        val repeatNote =
            repeat &&
                (item.workflow.style(next).log.leave == "note" ||
                    item.workflow.style(reopen).log.enter == "note" ||
                    when (item.agendaStamp?.keyword) {
                        "SCHEDULED" -> workspace.configuration.logging.reschedule == "note"
                        "DEADLINE" -> workspace.configuration.logging.redeadline == "note"
                        else -> false
                    })
        if (
            repeatNote ||
                item.workflow.style(next).log.enter == "note" ||
                item.workflow.style(item.state).log.leave == "note" ||
                item.workflow.terminal(next) && workspace.configuration.logging.done == "note"
        )
            completionNote = item
        else model.toggle(item)
    }
    OrgendaTheme(preferences) {
        TodayProvider {
            CompositionLocalProvider(
                LocalModalCount provides modalCount,
                LocalSwipeActionsCoordinator provides swipeActions,
            ) {
                BackHandler(editor != null) {
                    if (editor?.error != null) leaveDraft = true else model.closeEditor()
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val expanded = maxWidth >= 600.dp
                    val landscape =
                        LocalConfiguration.current.orientation ==
                            Configuration.ORIENTATION_LANDSCAPE
                    val twoPane = maxWidth >= 840.dp && LocalDensity.current.fontScale < 1.5f
                    val calendarVisible =
                        page == "calendar" &&
                            editor == null &&
                            !starting &&
                            workspace.workspace != null
                    val destinations =
                        listOf(
                            "agenda" to Icons.Default.Checklist,
                            "calendar" to Icons.Default.CalendarMonth,
                            "files" to Icons.Default.Folder,
                            "search" to Icons.Default.Search,
                        )
                    val inactiveIcons =
                        mapOf(
                            "agenda" to Icons.Outlined.Checklist,
                            "calendar" to Icons.Outlined.CalendarMonth,
                            "files" to Icons.Outlined.Folder,
                            "search" to Icons.Outlined.Search,
                        )
                    Scaffold(
                        topBar = {
                            if (calendarVisible)
                                CalendarTopAppBar(calendarState, { date = it.toString() })
                            else if (
                                page == "files" &&
                                    !starting &&
                                    filesState != null &&
                                    (editor == null || twoPane)
                            )
                                FilesTopAppBar(model, workspace, filesState) { openSettings() }
                            else if (!calendarVisible && (editor == null || expanded))
                                TopAppBar(
                                    title = {
                                        if (page == "agenda")
                                            AgendaPerspectiveMenu(
                                                workspace,
                                                perspective,
                                                { perspective = it },
                                                enabled = !starting && workspace.workspace != null,
                                            )
                                        else Text(tr(page))
                                    },
                                    actions = {
                                        if (workspace.syncing) {
                                            val syncLabel = tr("syncing")
                                            Box(
                                                Modifier.size(UiSizes.touchTarget),
                                                contentAlignment = androidx.compose.ui.Alignment.Center,
                                            ) {
                                                CircularProgressIndicator(
                                                    Modifier.size(UiSizes.progressIndicator)
                                                        .semantics { contentDescription = syncLabel },
                                                    strokeWidth = UiSizes.progressStroke,
                                                )
                                            }
                                        }
                                        if (page != "agenda" && page != "search")
                                            IconButton(
                                                onClick = { openSettings() },
                                                enabled = !starting,
                                            ) {
                                                Icon(Icons.Outlined.Settings, tr("settings"))
                                            }
                                    },
                                )
                        },
                        bottomBar = {
                            if (!expanded && editor == null)
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    tonalElevation = 0.dp,
                                ) {
                                    destinations.forEach { (key, icon) ->
                                        NavigationBarItem(
                                            enabled = !starting,
                                            selected = page == key,
                                            onClick = { navigate(key) },
                                            icon = {
                                                Icon(
                                                    if (page == key) icon
                                                    else inactiveIcons.getValue(key),
                                                    if (landscape) tr(key) else null,
                                                )
                                            },
                                            label =
                                                if (landscape) null
                                                else {
                                                    { Text(tr(key)) }
                                                },
                                        )
                                    }
                                }
                        },
                        floatingActionButton = {
                            if (
                                !starting &&
                                    workspace.workspace != null &&
                                    editor == null &&
                                    page == "agenda"
                            )
                                FloatingActionButton(
                                    onClick = { startCapture() },
                                    shape = CircleShape,
                                    elevation = subtleFabElevation(),
                                ) {
                                    Icon(Icons.Default.Add, tr("new_task"))
                                }
                        },
                        snackbarHost = { SnackbarHost(snackbar) },
                    ) { insets ->
                        Row(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                            if (expanded)
                                NavigationRail(
                                    modifier =
                                        Modifier.fillMaxHeight()
                                            .background(MaterialTheme.colorScheme.surfaceContainer)
                                            .verticalScroll(rememberScrollState()),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ) {
                                    destinations.forEach { (key, icon) ->
                                        NavigationRailItem(
                                            enabled = !starting,
                                            selected = page == key,
                                            onClick = { navigate(key) },
                                            icon = {
                                                Icon(
                                                    if (page == key) icon
                                                    else inactiveIcons.getValue(key),
                                                    if (landscape) tr(key) else null,
                                                )
                                            },
                                            label =
                                                if (landscape) null
                                                else {
                                                    {
                                                        Text(
                                                            tr(key),
                                                            Modifier.padding(horizontal = UiSpacing.sm),
                                                        )
                                                    }
                                                },
                                        )
                                    }
                                }
                            if (starting)
                                Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = androidx.compose.ui.Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            else if (workspace.workspace == null)
                                Column(
                                    Modifier.fillMaxSize()
                                        .padding(horizontal = UiSpacing.edge, vertical = UiSpacing.xl),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment =
                                        androidx.compose.ui.Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        tr("workspace_unavailable"),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    startupError?.let {
                                        Text(it, color = MaterialTheme.colorScheme.error)
                                    }
                                    Button(
                                        shape = MaterialTheme.shapes.medium,
                                        onClick = {
                                            starting = true
                                            coroutineScope.launch {
                                                try {
                                                    withContext(Dispatchers.IO) {
                                                        model.repository.start()
                                                    }
                                                    startupError = null
                                                } catch (e: Exception) {
                                                    startupError = e.message
                                                } finally {
                                                    starting = false
                                                }
                                            }
                                        }
                                    ) {
                                        Text(tr("retry"))
                                    }
                                    TextButton(shape = MaterialTheme.shapes.medium, onClick = { openSettings("workspace") }) {
                                        Text(tr("local_folder"))
                                    }
                                }
                            else
                                PageTransition(
                                    AppPage(page, editor.takeIf { !twoPane || page != "files" }),
                                    Modifier.weight(1f).fillMaxHeight(),
                                    contentKey = { it.key },
                                    direction = { previous, next ->
                                        when {
                                            next.document != null -> 1
                                            previous.document != null -> -1
                                            else -> 0
                                        }
                                    },
                                ) { shown ->
                                    val document = shown.document
                                    if (document != null) {
                                        DocumentScreen(
                                            model,
                                            document,
                                            { editing = it },
                                            {
                                                if (document.error != null) leaveDraft = true
                                                else model.closeEditor()
                                            },
                                            toggle,
                                        )
                                    } else
                                        pageStates.SaveableStateProvider(shown.destination) {
                                            Row(Modifier.fillMaxSize()) {
                                                when (shown.destination) {
                                                    "agenda" ->
                                                        AgendaScreen(
                                                            workspace,
                                                            { editing = it },
                                                            toggle,
                                                            { model.archive(it) },
                                                            { refile = it },
                                                            perspective,
                                                            { perspective = it },
                                                            { item ->
                                                                workspace.files
                                                                    .firstOrNull {
                                                                        it.path == item.path
                                                                    }
                                                                    ?.let {
                                                                        model.open(it, item.start)
                                                                    }
                                                            },
                                                            { scheduling = it },
                                                        )
                                                    "calendar" ->
                                                        key(calendarNavigation) {
                                                            CalendarScreen(
                                                                workspace,
                                                                LocalDate.parse(date),
                                                                { date = it.toString() },
                                                                { editing = it },
                                                                toggle,
                                                                { journal = true },
                                                                { path, byte ->
                                                                    workspace.files
                                                                        .firstOrNull {
                                                                            it.path == path
                                                                        }
                                                                        ?.let {
                                                                            model.open(it, byte)
                                                                        }
                                                                },
                                                                {
                                                                    startCapture(
                                                                        LocalDate.parse(date)
                                                                    )
                                                                },
                                                                {
                                                                    perspective = "overdue"
                                                                    page = "agenda"
                                                                },
                                                                state = calendarState,
                                                                showTopBar = false,
                                                            ) { item, day ->
                                                                val rule =
                                                                    workspace.configuration.logging
                                                                        .reschedule
                                                                if (rule == "note")
                                                                    requestRescheduleNote(item, day)
                                                                else model.reschedule(item, day)
                                                            }
                                                        }
                                                    "files" -> {
                                                        Box(
                                                            Modifier.then(
                                                                if (twoPane) Modifier.width(320.dp)
                                                                else Modifier.weight(1f)
                                                            )
                                                        ) {
                                                            FilesScreen(
                                                                model,
                                                                workspace,
                                                                filesFolder,
                                                                requireNotNull(filesState),
                                                            ) {
                                                                filesFolder = null
                                                            }
                                                        }
                                                        if (twoPane) {
                                                            VerticalDivider()
                                                            PageTransition(
                                                                editor,
                                                                Modifier.weight(1f).fillMaxHeight(),
                                                                contentKey = { it?.session },
                                                                direction = { _, next ->
                                                                    if (next == null) -1 else 1
                                                                },
                                                            ) { selected ->
                                                                if (selected != null)
                                                                    DocumentScreen(
                                                                        model,
                                                                        selected,
                                                                        { editing = it },
                                                                        {
                                                                            if (
                                                                                selected.error !=
                                                                                    null
                                                                            )
                                                                                leaveDraft = true
                                                                            else model.closeEditor()
                                                                        },
                                                                        toggle,
                                                                    )
                                                                else EmptyState("select_file")
                                                            }
                                                        }
                                                    }
                                                    "search" ->
                                                        SearchScreen(
                                                            model,
                                                            workspace,
                                                            searchFocusRequest,
                                                            { folder ->
                                                                filesFolder = folder
                                                                page = "files"
                                                            },
                                                        ) { section ->
                                                            openSettings(section)
                                                        }
                                                }
                                            }
                                        }
                                }
                        }
                    }
                }
                if (settingsOpen)
                    SettingsSheet(model, workspace, settingsSection, { settingsSection = null }) {
                        settingsOpen = false
                    }
                if (capture) {
                    if (captureDate != null)
                        NewScheduledItemSheet(model, captureDate!!) { capture = false }
                    else CaptureSheet(model) { capture = false }
                }
                if (journal)
                    JournalSheet(LocalDate.parse(date), { journal = false }) { title, body ->
                        model.newJournal(LocalDate.parse(date), title, body)
                    }
                editing?.let { item -> ItemEditorSheet(model, item) { editing = null } }
                completionNote?.let { item ->
                    TextPrompt(
                        "completed",
                        label = "note",
                        multiline = true,
                        onDismiss = { completionNote = null },
                    ) { note ->
                        completionNote = null
                        model.toggle(item, note)
                    }
                }
                refile?.let { item -> RefileSheet(model, item) { refile = null } }
                scheduling?.let { item ->
                    RescheduleSheet(item, { scheduling = null }) { day ->
                        scheduling = null
                        if (workspace.configuration.logging.reschedule == "note")
                            requestRescheduleNote(item, day)
                        else model.reschedule(item, day)
                    }
                }
                rescheduleNoteItem?.let { item ->
                    TextPrompt(
                        "scheduled",
                        label = "note",
                        multiline = true,
                        onDismiss = { rescheduleNoteItem = null },
                    ) { note ->
                        model.reschedule(item, LocalDate.parse(rescheduleNoteDate), note)
                        rescheduleNoteItem = null
                    }
                }
                if (leaveDraft)
                    AlertDialog(
                        onDismissRequest = { leaveDraft = false },
                        title = { Text(tr("unsaved")) },
                        text = { Text(tr("save_failed")) },
                        confirmButton = {
                            TextButton(shape = MaterialTheme.shapes.medium, onClick = { leaveDraft = false }) {
                                Text(tr("continue_editing"))
                            }
                        },
                        dismissButton = {
                            TextButton(
                                shape = MaterialTheme.shapes.medium,
                                onClick = {
                                    model.discardEditor()
                                    leaveDraft = false
                                }
                            ) {
                                Text(tr("discard"))
                            }
                        },
                    )
            }
        }
    }
}
