@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.roifewu.orgenda.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.*
import com.roifewu.orgenda.platform.SyncWorker
import kotlinx.coroutines.*

@Composable
internal fun SettingsSheet(
    model: OrgendaViewModel,
    snapshot: WorkspaceSnapshot,
    initialSection: String? = null,
    consumed: () -> Unit = {},
    close: () -> Unit,
) {
    ModalPresence()
    val edit by model.configuration.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    fun dismiss() {
        if (saving) return
        if (edit?.dirty != true) {
            close()
            return
        }
        saving = true
        scope.launch {
            try {
                model.saveConfigurationNow()
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                discard = true
            } finally {
                saving = false
            }
        }
    }
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { next ->
                if (next == SheetValue.Hidden && edit?.dirty == true) {
                    dismiss()
                    false
                } else true
            },
        )
    ModalBottomSheet(onDismissRequest = ::dismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = UiSpacing.edge),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("settings"),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                )
                IconButton(onClick = ::dismiss, enabled = !saving) {
                    Icon(Icons.Default.Close, tr("close"))
                }
            }
            Box(Modifier.weight(1f)) { SettingsScreen(model, snapshot, initialSection, consumed) }
        }
    }
    if (discard) {
        ModalPresence()
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(tr("unsaved")) },
            text = { Text(tr("configuration_invalid")) },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.medium, onClick = { discard = false }) { Text(tr("continue_editing")) }
            },
            dismissButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        model.discardConfiguration()
                        discard = false
                        close()
                    }
                ) {
                    Text(tr("discard"))
                }
            },
        )
    }
}

@Composable
fun SettingsScreen(
    model: OrgendaViewModel,
    snapshot: WorkspaceSnapshot,
    initialSection: String? = null,
    consumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val largeSettingsText = LocalDensity.current.fontScale > 1.3f
    val clipboard = LocalClipboardManager.current
    val edit by model.configuration.collectAsStateWithLifecycle()
    val local by model.settings.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(initialSection) {
        if (initialSection != null) {
            section = initialSection.takeUnless { it == "workspace" }
            consumed()
        }
    }
    var workflowPreset by rememberSaveable { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    var webdav by remember { mutableStateOf(false) }
    var switch by remember { mutableStateOf<List<WorkspaceEntity>?>(null) }
    var conflict by remember { mutableStateOf<FileEntity?>(null) }
    var leave by remember { mutableStateOf(false) }
    var templatePreview by
        rememberSaveable(stateSaver = SettingsTemplateSaver) {
            mutableStateOf<CaptureTemplate?>(null)
        }
    var showPrompt by rememberSaveable { mutableStateOf(false) }
    var showJson by rememberSaveable { mutableStateOf(false) }
    var pastedImport by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var importPreview by remember(snapshot.workspace?.id) { mutableStateOf<WorkspaceConfig?>(null) }
    var undoImport by remember(snapshot.workspace?.id) { mutableStateOf<WorkspaceConfig?>(null) }
    var importReplacement by
        remember(snapshot.workspace?.id) { mutableStateOf<WorkspaceConfig?>(null) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    permissionRevision++
                    model.action {
                        model.app.reminders.refresh(
                            model.workspace.value,
                            model.settings.value.notifications,
                            true,
                        )
                    }
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val scope = rememberCoroutineScope()
    val config = edit?.value ?: snapshot.configuration
    var promptCopied by remember(config) { mutableStateOf(false) }
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null)
                model.action {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    model.saveConfigurationNow()
                    model.repository.connectFolder(
                        uri,
                        androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
                            ?: context.label("workspace"),
                    )
                }
        }
    val recovery =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            if (uri != null)
                model.action {
                    context.contentResolver.openOutputStream(uri)?.let {
                        model.repository.exportRecovery(it)
                    } ?: error("Export destination unavailable")
                }
        }
    val import =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                model.action {
                    val source =
                        context.contentResolver.openInputStream(uri)?.use {
                            it.readNBytes(1024 * 1024 + 1)
                        } ?: error("Cannot open configuration")
                    require(source.size <= 1024 * 1024)
                    importPreview = ConfigurationCodec.decode(source.strictText())
                }
        }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null)
                model.action {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(ConfigurationCodec.encode(config).toByteArray())
                    }
                }
        }
    val notifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissionRevision++
            model.action {
                model.app.reminders.refresh(
                    model.workspace.value,
                    model.settings.value.notifications,
                    true,
                )
            }
        }
    fun back() {
        if (edit?.dirty == true)
            scope.launch {
                try {
                    model.saveConfigurationNow()
                    section = null
                } catch (_: Exception) {
                    leave = true
                }
            }
        else section = null
    }
    BackHandler(LocalPageActive.current && section != null) { back() }
    val sectionStates = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalSettingsLayout provides true) {
        PageTransition(
            section,
            Modifier.fillMaxSize(),
            direction = { _, next -> if (next == null) -1 else 1 },
        ) { shownSection ->
            sectionStates.SaveableStateProvider(shownSection ?: "root") {
                Column(Modifier.fillMaxSize()) {
                    if (shownSection != null)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = UiSpacing.xs, vertical = UiSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = ::back) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("back"))
                            }
                            Text(
                                tr(shownSection),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f).padding(start = UiSpacing.sm),
                            )
                            if (shownSection == "workflow" && !largeSettingsText)
                                OutlinedButton(
                                    onClick = { workflowPreset = true },
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.widthIn(max = 136.dp).padding(end = UiSpacing.md),
                                    contentPadding = PaddingValues(horizontal = UiSpacing.sm, vertical = UiSpacing.xs),
                                ) { Text(tr("workflow_preset")) }
                            if (edit?.saving == true)
                                Box(Modifier.size(UiSizes.touchTarget), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(UiSizes.progressIndicator), strokeWidth = UiSizes.progressStroke)
                                }
                            else if (edit?.dirty == true)
                                TextButton(
                                    shape = MaterialTheme.shapes.medium,
                                    onClick = { model.action { model.saveConfigurationNow() } }
                                ) {
                                    Text(tr("retry"))
                                }
                        }
                    if (shownSection == "workflow" && largeSettingsText)
                        OutlinedButton(
                            onClick = { workflowPreset = true },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.padding(horizontal = UiSpacing.edge).fillMaxWidth(),
                        ) { Text(tr("workflow_preset")) }
                    edit?.error?.let {
                        Text(it, Modifier.padding(UiSpacing.edge), color = MaterialTheme.colorScheme.error)
                    }
                    if (shownSection == null)
                        LazyColumn(contentPadding = PaddingValues(top = UiSpacing.sm, bottom = UiSpacing.lg)) {
                            item { SectionTitle("workspace", Modifier.padding(horizontal = UiSpacing.edge).padding(top = UiSpacing.xs, bottom = UiSpacing.xs)) }
                            item {
                                ListItem(
                                    headlineContent = { Text(snapshot.workspace?.name ?: "Orgenda") },
                                    supportingContent = {
                                        Text(
                                            tr(
                                                if (snapshot.syncing) "syncing"
                                                else if (snapshot.pending > 0) "pending" else "synced"
                                            ) +
                                                if (snapshot.pending > 0) " · ${snapshot.pending}"
                                                else ""
                                        )
                                    },
                                )
                            }
                            snapshot.workspace?.let { workspace ->
                                item {
                                    androidx.compose.foundation.text.selection.SelectionContainer {
                                        Text(
                                            workspace.root,
                                            Modifier.padding(horizontal = UiSpacing.edge),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                if (workspace.kind == "webdav" && workspace.username.isNotBlank())
                                    item {
                                        ListItem(
                                            headlineContent = { Text(tr("username")) },
                                            supportingContent = { Text(workspace.username) },
                                        )
                                    }
                            }
                            snapshot.syncError?.let { message ->
                                item {
                                    Text(
                                        message,
                                        Modifier.padding(UiSpacing.edge),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            snapshot.workspace
                                ?.lastSync
                                ?.takeIf { it > 0 }
                                ?.let { checked ->
                                    item {
                                        Text(
                                            tr("last_checked") +
                                                ": " +
                                                java.time.Instant.ofEpochMilli(checked)
                                                    .atZone(java.time.ZoneId.systemDefault())
                                                    .format(
                                                        java.time.format.DateTimeFormatter
                                                            .ofLocalizedDateTime(
                                                                java.time.format.FormatStyle.MEDIUM
                                                            )
                                                    ),
                                            Modifier.padding(horizontal = UiSpacing.edge),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            if (snapshot.workspace?.kind != "private")
                                item {
                                    SettingRow("disconnect", !snapshot.syncing) { disconnect = true }
                                }
                            item {
                                SettingRow("local_folder", !snapshot.syncing) {
                                    folderPicker.launch(null)
                                }
                            }
                            item { SettingRow("connect_dav", !snapshot.syncing) { webdav = true } }
                            item {
                                SettingRow("switch_workspace", !snapshot.syncing) {
                                    scope.launch { switch = model.repository.workspaces() }
                                }
                            }
                            item {
                                SettingRow("recovery_export") {
                                    recovery.launch("orgenda-recovery.zip")
                                }
                            }
                            item {
                                Text(
                                    tr("switch_hint"),
                                    Modifier.padding(
                                        horizontal = UiSpacing.edge,
                                        vertical = UiSpacing.supportingTextExtraVertical,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (snapshot.workspace?.kind == "saf")
                                item {
                                    Text(
                                        tr("folder_limit"),
                                        Modifier.padding(
                                            horizontal = UiSpacing.edge,
                                            vertical = UiSpacing.edge + UiSpacing.supportingTextExtraVertical,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            if (snapshot.conflicts.isNotEmpty()) {
                                item { SectionTitle("conflicts", Modifier.padding(horizontal = UiSpacing.edge).padding(top = UiSpacing.md, bottom = UiSpacing.xs)) }
                                items(snapshot.conflicts, key = { it.id }) { file ->
                                    ListItem(
                                        headlineContent = { Text(file.path) },
                                        modifier = Modifier.clickable { conflict = file },
                                    )
                                }
                            }
                            items(snapshot.operations.filter { it.error != null }, key = { it.id }) {
                                operation ->
                                ListItem(
                                    headlineContent = { Text(operation.to) },
                                    supportingContent = { Text(operation.error ?: "") },
                                    trailingContent = {
                                        TextButton(
                                            shape = MaterialTheme.shapes.medium,
                                            onClick = {
                                                model.action {
                                                    model.repository.retryOperation(operation.id)
                                                }
                                            }
                                        ) {
                                            Text(tr("retry"))
                                        }
                                    },
                                )
                            }
                            item { SectionTitle("settings", Modifier.padding(horizontal = UiSpacing.edge).padding(top = UiSpacing.md, bottom = UiSpacing.xs)) }
                            listOf(
                                    "workflow",
                                    "capture_templates",
                                    "files_agenda",
                                    "reminders",
                                    "configuration",
                                )
                                .forEach { key -> item { SettingRow(key) { section = key } } }
                            item {
                                Box(Modifier.padding(start = UiSpacing.edge, top = UiSpacing.md, end = UiSpacing.edge)) {
                                    Choice(
                                        local.theme,
                                        listOf("system", "light", "dark"),
                                        "appearance",
                                        model.app.settings::theme,
                                        presentation = ChoicePresentation.Segmented,
                                    )
                                }
                            }
                        }
                    else
                        Column(
                            Modifier.fillMaxWidth()
                                .imePadding()
                                .verticalScroll(rememberScrollState())
                                .padding(start = UiSpacing.edge, top = UiSpacing.sm, end = UiSpacing.edge, bottom = UiSpacing.lg),
                            verticalArrangement = Arrangement.spacedBy(UiSpacing.md),
                        ) {
                            when (shownSection) {
                                "appearance" ->
                                    Choice(
                                        local.theme,
                                        listOf("system", "light", "dark"),
                                        "appearance",
                                        model.app.settings::theme,
                                        presentation = ChoicePresentation.Segmented,
                                    )
                                "workflow" ->
                                    WorkflowSettings(
                                        config,
                                        snapshot.workspace?.id.orEmpty(),
                                        { workspaceId, expected, value ->
                                            model.saveConfigurationEdit(value, expected, workspaceId)
                                        },
                                        presetRequested = workflowPreset,
                                        consumePreset = { workflowPreset = false },
                                        showPresetButton = false,
                                    ) {
                                        model.configure(it)
                                    }
                                "capture_templates" ->
                                    CaptureSettings(
                                        config,
                                        snapshot,
                                        { templatePreview = it },
                                        { workspaceId, expected, value ->
                                            model.saveConfigurationEdit(value, expected, workspaceId)
                                        },
                                    ) {
                                        model.configure(it)
                                    }
                                "files_agenda" -> FileSettings(config, snapshot) { model.configure(it) }
                                "reminders" -> {
                                    SettingsGroup {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(tr("enable_reminders"), Modifier.weight(1f))
                                            Switch(
                                                local.notifications,
                                                { enabled ->
                                                    model.app.settings.notifications(enabled)
                                                    if (enabled)
                                                        notifications.launch(
                                                            Manifest.permission.POST_NOTIFICATIONS
                                                        )
                                                },
                                            )
                                        }
                                        OutlinedButton(
                                            shape = MaterialTheme.shapes.medium,
                                            onClick = {
                                                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        ) {
                                            Text(tr("notification_permission"))
                                        }
                                        OutlinedButton(
                                            shape = MaterialTheme.shapes.medium,
                                            onClick = {
                                                context.startActivity(
                                                    Intent(
                                                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                                        Uri.parse("package:${context.packageName}"),
                                                    )
                                                )
                                            }
                                        ) {
                                            Text(tr("exact_permission"))
                                        }
                                        val permitted =
                                            remember(permissionRevision) {
                                                model.app.reminders.authorized() to
                                                    model.app.reminders.exact()
                                            }
                                        Text(
                                            tr("notification_permission") +
                                                ": " +
                                                tr(if (permitted.first) "allowed" else "not_allowed") +
                                                "\n" +
                                                tr("exact_reminders") +
                                                ": " +
                                                tr(if (permitted.second) "allowed" else "not_allowed"),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        TextButton(
                                            shape = MaterialTheme.shapes.medium,
                                            onClick = {
                                                context.startActivity(
                                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                        .putExtra(
                                                            Settings.EXTRA_APP_PACKAGE,
                                                            context.packageName,
                                                        )
                                                )
                                            }
                                        ) {
                                            Text(tr("notification_settings"))
                                        }
                                        Text(
                                            tr("reminder_degraded"),
                                            Modifier.padding(vertical = UiSpacing.supportingTextExtraVertical),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    ReminderTimingSetting(config.reminders.advanceMinutes, "advance") {
                                        model.configure(
                                            config.copy(
                                                reminders = config.reminders.copy(advanceMinutes = it)
                                            )
                                        )
                                    }
                                    ReminderTimingSetting(
                                        config.reminders.repeatMinutes,
                                        "repeat_minutes",
                                    ) {
                                        model.configure(
                                            config.copy(
                                                reminders = config.reminders.copy(repeatMinutes = it)
                                            )
                                        )
                                    }
                                    ReminderTimingSetting(
                                        config.reminders.deadlineWarningDays,
                                        "warning_days",
                                    ) {
                                        model.configure(
                                            config.copy(
                                                reminders =
                                                    config.reminders.copy(deadlineWarningDays = it)
                                            )
                                        )
                                    }
                                    val rules = config.reminders
                                    if (
                                        rules.advanceMinutes in 0..1440 &&
                                            rules.repeatMinutes in 1..1440
                                    ) {
                                        val offsets =
                                            ((rules.advanceMinutes downTo 0 step rules.repeatMinutes)
                                                    .toList() + 0)
                                                .distinct()
                                        Text(
                                            offsets.take(6).joinToString(" → ") {
                                                if (it == 0) context.label("starting_now")
                                                else "−${it}m"
                                            } +
                                                if (offsets.size > 6) " → … → " + tr("starting_now")
                                                else "",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                "configuration" -> {
                                    SettingsGroup {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm),
                                            verticalArrangement = Arrangement.spacedBy(UiSpacing.sm),
                                        ) {
                                            OutlinedButton(
                                                shape = MaterialTheme.shapes.medium,
                                                onClick = {
                                                    import.launch(arrayOf("application/json", "text/*"))
                                                }
                                            ) {
                                                Text(tr("import_json"))
                                            }
                                            OutlinedButton(
                                                onClick = { export.launch("config.json") },
                                                shape = MaterialTheme.shapes.medium,
                                            ) {
                                                Text(tr("export_json"))
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { pastedImport = "" },
                                            shape = MaterialTheme.shapes.medium,
                                        ) {
                                            Text(tr("paste_configuration"))
                                        }
                                    }
                                    SettingsGroup {
                                        TextButton(shape = MaterialTheme.shapes.medium, onClick = { showJson = !showJson }) {
                                            Text(tr("view_configuration"))
                                            Icon(
                                                if (showJson) Icons.Default.ExpandLess
                                                else Icons.Default.ExpandMore,
                                                null,
                                            )
                                        }
                                        if (showJson || edit?.error != null)
                                            Field(
                                                edit?.raw ?: ConfigurationCodec.encode(config),
                                                model::configureRaw,
                                                "configuration",
                                                singleLine = false,
                                            )
                                        undoImport
                                            ?.takeIf { config == importReplacement }
                                            ?.let { previous ->
                                                TextButton(
                                                    shape = MaterialTheme.shapes.medium,
                                                    onClick = {
                                                        model.configure(previous)
                                                        undoImport = null
                                                        importReplacement = null
                                                    },
                                                    enabled = edit?.dirty != true && edit?.saving != true,
                                                ) {
                                                    Text(tr("undo"))
                                                }
                                            }
                                    }
                                    SettingsGroup {
                                        OutlinedButton(
                                            shape = MaterialTheme.shapes.medium,
                                            onClick = {
                                                clipboard.setText(
                                                    AnnotatedString(ConfigurationPrompt.text(config))
                                                )
                                                promptCopied = true
                                            }
                                        ) {
                                            Text(tr(if (promptCopied) "copied" else "emacs_prompt"))
                                        }
                                        TextButton(shape = MaterialTheme.shapes.medium, onClick = { showPrompt = !showPrompt }) {
                                            Text(tr("preview"))
                                        }
                                        if (showPrompt)
                                            androidx.compose.foundation.text.selection.SelectionContainer {
                                                Text(
                                                    ConfigurationPrompt.text(config),
                                                    Modifier.padding(vertical = UiSpacing.supportingTextExtraVertical),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                    }
                                    TextButton(shape = MaterialTheme.shapes.medium, onClick = { confirmReset = true }) { Text(tr("reset")) }
                                }
                            }
                        }
                }
            }
        }
    }
    importPreview?.let { imported ->
        ModalPresence()
        AlertDialog(
            onDismissRequest = { importPreview = null },
            title = { Text(tr("import_json")) },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        ConfigurationCodec.encode(imported),
                        Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        undoImport = config
                        importReplacement = imported
                        model.configure(imported)
                        importPreview = null
                    }
                ) {
                    Text(tr("save"))
                }
            },
            dismissButton = {
                TextButton(shape = MaterialTheme.shapes.medium, onClick = { importPreview = null }) { Text(tr("cancel")) }
            },
        )
    }
    pastedImport?.let { initial ->
        ConfigurationPasteDialog(initial, { pastedImport = null }) {
            importPreview = it
            pastedImport = null
        }
    }
    templatePreview?.let {
        CaptureSheet(model, initialTemplate = it, previewOnly = true) { templatePreview = null }
    }
    if (disconnect) {
        ModalPresence()
        AlertDialog(
            onDismissRequest = { disconnect = false },
            title = { Text(tr("disconnect")) },
            text = { Text(tr("switch_hint")) },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        model.action {
                            model.saveConfigurationNow()
                            model.repository.disconnect()
                        }
                        disconnect = false
                    }
                ) {
                    Text(tr("disconnect"))
                }
            },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.medium, onClick = { disconnect = false }) { Text(tr("cancel")) } },
        )
    }
    if (webdav) WebDavDialog(model) { webdav = false }
    switch?.let { workspaces ->
        ModalPresence()
        AlertDialog(
            onDismissRequest = { switch = null },
            title = { Text(tr("switch_workspace")) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    workspaces.forEach { workspace ->
                        TextButton(
                            shape = MaterialTheme.shapes.medium,
                            onClick = {
                                model.action {
                                    model.saveConfigurationNow()
                                    model.repository.switchWorkspace(workspace.id)
                                }
                                switch = null
                            }
                        ) {
                            Text(workspace.name + " · " + workspace.kind)
                        }
                    }
                }
            },
            confirmButton = { TextButton(shape = MaterialTheme.shapes.medium, onClick = { switch = null }) { Text(tr("close")) } },
        )
    }
    conflict?.let { file -> ConflictDialog(model, file) { conflict = null } }
    if (leave) {
        ModalPresence()
        AlertDialog(
            onDismissRequest = { leave = false },
            title = { Text(tr("unsaved")) },
            text = { Text(tr("configuration_invalid")) },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.medium, onClick = { leave = false }) { Text(tr("continue_editing")) }
            },
            dismissButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        model.discardConfiguration()
                        leave = false
                        section = null
                    }
                ) {
                    Text(tr("discard"))
                }
            },
        )
    }
    if (confirmReset) {
        ModalPresence()
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(tr("reset")) },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        undoImport = config
                        importReplacement = WorkspaceConfig.defaults()
                        model.configure(WorkspaceConfig.defaults())
                        confirmReset = false
                    }
                ) {
                    Text(tr("reset"))
                }
            },
            dismissButton = {
                TextButton(shape = MaterialTheme.shapes.medium, onClick = { confirmReset = false }) { Text(tr("cancel")) }
            },
        )
    }
}

@Composable
private fun SettingRow(key: String, enabled: Boolean = true, click: () -> Unit) {
    ListItem(
        headlineContent = { Text(tr(key)) },
        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        modifier = Modifier.clickable(enabled = enabled, onClick = click),
    )
}

@Composable
private fun WebDavDialog(model: OrgendaViewModel, close: () -> Unit) {
    ModalPresence()
    val snapshot by model.workspace.collectAsState()
    val workspace = snapshot.workspace?.takeIf { it.kind == "webdav" }
    var address by remember { mutableStateOf(workspace?.root ?: "https://") }
    var username by remember { mutableStateOf(workspace?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!connecting) close() },
        title = { Text(tr("connect_dav")) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState())
                    .padding(top = if (LocalDensity.current.fontScale > 1.3f) UiSpacing.lg else UiSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.md),
            ) {
                Field(
                    address,
                    { address = it },
                    "address",
                    enabled = !connecting,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Field(
                    username,
                    { username = it },
                    "username",
                    enabled = !connecting,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
                Field(
                    password,
                    { password = it },
                    "password",
                    enabled = !connecting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                shape = MaterialTheme.shapes.medium,
                enabled =
                    !connecting &&
                        address.isNotBlank() &&
                        username.isNotBlank() &&
                        password.isNotEmpty(),
                onClick = {
                    connecting = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                model.saveConfigurationNow()
                                model.repository.connectWebDav(
                                    address.trim(),
                                    username.trim(),
                                    password,
                                )
                            }
                            close()
                        } catch (e: Exception) {
                            error = e.message
                            connecting = false
                        }
                    }
                },
            ) {
                Text(tr("connect"))
            }
        },
        dismissButton = {
            TextButton(shape = MaterialTheme.shapes.medium, onClick = close, enabled = !connecting) { Text(tr("cancel")) }
        },
    )
}

@Composable
private fun ConflictDialog(model: OrgendaViewModel, file: FileEntity, close: () -> Unit) {
    ModalPresence()
    var local by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            local = model.repository.text(file)
            remote = file.remoteBlob?.let(model.repository.blobs::text) ?: ""
        }
    }
    fun resolve(choice: String) {
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    model.repository.resolve(file.id, choice, file.generation)
                }
                SyncWorker.enqueue(model.app, file.workspaceId)
                close()
            } catch (e: Exception) {
                error = e.message
                saving = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) close() },
        title = { Text(file.path) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.md),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.sm),
                ) {
                    FilterChip(!tab, { tab = false }, label = { Text(tr("local_version")) })
                    FilterChip(tab, { tab = true }, label = { Text(tr("remote_version")) })
                }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        if (tab && file.remoteMissing) tr("remote_deleted")
                        else if (tab) remote else local,
                        Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(shape = MaterialTheme.shapes.medium, onClick = { confirmation = "local" }, enabled = !saving) {
                    Text(tr("keep_local"))
                }
                TextButton(shape = MaterialTheme.shapes.medium, onClick = { confirmation = "remote" }, enabled = !saving) {
                    Text(tr("keep_remote"))
                }
            }
        },
        confirmButton = {
            TextButton(shape = MaterialTheme.shapes.medium, onClick = { resolve("both") }, enabled = !saving) { Text(tr("keep_both")) }
        },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.medium, onClick = close, enabled = !saving) { Text(tr("cancel")) } },
    )
    confirmation?.let { choice ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(tr(if (choice == "local") "keep_local" else "keep_remote")) },
            text = { Text(tr("confirm_conflict_version")) },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        confirmation = null
                        resolve(choice)
                    }
                ) {
                    Text(tr("confirm"))
                }
            },
            dismissButton = {
                TextButton(shape = MaterialTheme.shapes.medium, onClick = { confirmation = null }) { Text(tr("cancel")) }
            },
        )
    }
}

@Composable
internal fun ConfigurationPasteDialog(
    initial: String = "",
    close: () -> Unit,
    apply: (WorkspaceConfig) -> Unit,
) {
    ModalPresence()
    var source by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    fun dismiss() {
        if (source != initial) discard = true else close()
    }
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { next ->
                if (next == SheetValue.Hidden && source != initial) {
                    discard = true
                    false
                } else true
            },
        )
    ModalBottomSheet(onDismissRequest = ::dismiss, sheetState = sheetState) {
        SettingsSheetContent("paste_configuration") {
            Field(
                source,
                {
                    source = it
                    error = null
                },
                "configuration",
                singleLine = false,
                modifier = Modifier.heightIn(min = 220.dp),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SettingsActions {
                TextButton(shape = MaterialTheme.shapes.medium, onClick = ::dismiss) { Text(tr("cancel")) }
                Button(
                    shape = MaterialTheme.shapes.medium,
                    enabled = source.isNotBlank(),
                    onClick = {
                        runCatching { ConfigurationCodec.decode(source) }
                            .onSuccess(apply)
                            .onFailure { error = it.message }
                    },
                ) {
                    Text(tr("import_json"))
                }
            }
        }
    }
    if (discard) DiscardDialog({ discard = false }, close)
}

@Composable
private fun ReminderTimingSetting(value: Int, label: String, change: (Int) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val presets =
        when (label) {
            "advance" -> listOf(0, 5, 15, 30)
            "repeat_minutes" -> listOf(1, 5, 10, 15)
            else -> listOf(0, 3, 7, 14)
        }
    val range =
        when (label) {
            "advance" -> 0..1440
            "repeat_minutes" -> 1..1440
            else -> 0..365
        }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiSpacing.xs)) {
        SettingsValueRow(label, value.toString(), true, { editing = true })
        Choice(
            value.toString(),
            presets.map(Int::toString),
            "common_values",
            { change(it.toInt()) },
            presentation = ChoicePresentation.Chips,
            labels = { it },
        )
    }
    if (editing) {
        ModalPresence()
        var draft by rememberSaveable { mutableIntStateOf(value) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(tr(label)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState())
                        .padding(top = if (LocalDensity.current.fontScale > 1.3f) UiSpacing.lg else UiSpacing.xs)
                ) {
                    NumberField(draft, label) { draft = it }
                }
            },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        change(draft)
                        editing = false
                    },
                    enabled = draft in range,
                ) {
                    Text(tr("save"))
                }
            },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.medium, onClick = { editing = false }) { Text(tr("cancel")) } },
        )
    }
}
