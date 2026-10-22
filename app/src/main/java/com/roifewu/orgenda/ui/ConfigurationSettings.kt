@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roifewu.orgenda.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.WorkspaceSnapshot
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

typealias ConfigurationCommit =
    suspend (workspaceId: String, expected: WorkspaceConfig, value: WorkspaceConfig) -> Unit

private val SettingsConfigurationSaver =
    Saver<WorkspaceConfig, String>(
        save = { Json.encodeToString(WorkspaceConfig.serializer(), it) },
        restore = { Json.decodeFromString(WorkspaceConfig.serializer(), it) },
    )

internal val SettingsTemplateSaver =
    Saver<CaptureTemplate?, String>(
        save = { template ->
            template?.let { Json.encodeToString(CaptureTemplate.serializer(), it) }
        },
        restore = { Json.decodeFromString(CaptureTemplate.serializer(), it) },
    )
private val KeywordStyleSaver =
    Saver<KeywordStyle, String>(
        save = { Json.encodeToString(KeywordStyle.serializer(), it) },
        restore = { Json.decodeFromString(KeywordStyle.serializer(), it) },
    )
private val TemplateDraftSaver =
    Saver<CaptureTemplate, String>(
        save = { Json.encodeToString(CaptureTemplate.serializer(), it) },
        restore = { Json.decodeFromString(CaptureTemplate.serializer(), it) },
    )

@Composable
fun NumberField(value: Int, label: String, enabled: Boolean = true, change: (Int) -> Unit) {
    var text by rememberSaveable {
        mutableStateOf(if (value == Int.MIN_VALUE) "" else value.toString())
    }
    LaunchedEffect(value) {
        if (value != Int.MIN_VALUE && text.toIntOrNull() != value) text = value.toString()
    }
    val range =
        when (label) {
            "advance",
            "warning_minutes" -> 0..1440
            "repeat_minutes" -> 1..1440
            "warning_days" -> 0..365
            "empty_lines" -> 0..10
            "max_level" -> 1..99
            "duration" -> 5..480
            else -> 1..Int.MAX_VALUE
        }
    val invalid = text.toIntOrNull()?.let { it !in range } ?: true
    Field(
        text,
        {
            text = it
            change(it.toIntOrNull() ?: Int.MIN_VALUE)
        },
        label,
        invalid = invalid,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supporting = if (invalid) tr("enter_integer") + " (${range.first}–${range.last})" else null,
    )
    val presets =
        when (label) {
            "advance" -> listOf(0, 5, 10, 15, 30, 60)
            "repeat_minutes" -> listOf(1, 5, 10, 15)
            "warning_days" -> listOf(0, 7, 14, 30)
            "max_level" -> listOf(1, 2, 3, 5, 10)
            else -> emptyList()
        }
    if (presets.isNotEmpty())
        Choice(
            value.toString(),
            presets.map(Int::toString),
            "common_values",
            { change(it.toInt()) },
            enabled = enabled,
            presentation = ChoicePresentation.Chips,
        )
}

@Composable
fun ValuesField(
    values: List<String>,
    label: String,
    lines: Boolean = true,
    enabled: Boolean = true,
    change: (List<String>) -> Unit,
) {
    val separator = if (lines) "\n" else " "
    fun parse(text: String) =
        if (lines) text.lines().map(String::trim).filter(String::isNotBlank)
        else text.split(Regex("\\s+")).filter(String::isNotEmpty)
    var text by rememberSaveable { mutableStateOf(values.joinToString(separator)) }
    LaunchedEffect(values) { if (parse(text) != values) text = values.joinToString(separator) }
    Field(
        text,
        {
            text = it
            change(parse(it))
        },
        label,
        singleLine = !lines,
        enabled = enabled,
    )
}

@Composable
fun WorkflowSettings(
    config: WorkspaceConfig,
    workspaceId: String,
    persist: ConfigurationCommit,
    change: (WorkspaceConfig) -> Unit,
) {
    val workflow = config.workflow
    val sequence = workflow.sequences.first()
    var pendingSequence by remember { mutableStateOf<WorkspaceConfig?>(null) }
    var editingState by rememberSaveable { mutableStateOf(false) }
    var editingToken by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTerminal by rememberSaveable { mutableStateOf(false) }
    var preset by rememberSaveable { mutableStateOf(false) }
    var undo by remember { mutableStateOf<WorkspaceConfig?>(null) }
    fun applySequence(value: WorkflowSequence) {
        val updated =
            value.copy(
                initial = value.initial.takeIf { it in value.process } ?: value.process.first(),
                complete = value.complete.takeIf { it in value.terminal } ?: value.terminal.first(),
                reopen = value.reopen.takeIf { it in value.process } ?: value.process.first(),
            )
        val next =
            config.copy(
                workflow =
                    workflow.copy(
                        sequences = listOf(updated),
                        keywords =
                            workflow.keywords.filterKeys {
                                it in updated.process + updated.terminal
                            },
                    )
            )
        if (
            updated.initial != value.initial ||
                updated.complete != value.complete ||
                updated.reopen != value.reopen
        )
            pendingSequence = next
        else change(next)
    }
    fun display(token: String): String =
        workflow
            .style(token)
            .label
            .ifBlank { token }
            .let { if (it == token) token else "$it ($token)" }
    undo
        ?.takeIf { workflow == WorkspaceConfig.classic() }
        ?.let { previous ->
            TextButton(
                onClick = {
                    change(config.copy(workflow = previous.workflow))
                    undo = null
                }
            ) {
                Text(tr("undo"))
            }
        }
    OutlinedButton(onClick = { preset = true }) { Text(tr("classic")) }
    listOf(false, true).forEach { terminal ->
        val tokens = if (terminal) sequence.terminal else sequence.process
        SectionTitle(if (terminal) "terminal_states" else "process_states")
        tokens.forEachIndexed { index, token ->
            key(token) {
                var actions by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(display(token)) },
                    supportingContent =
                        workflow.style(token).key.takeIf(String::isNotBlank)?.let { { Text(it) } },
                    leadingContent = {
                        ConfigurationStateIcon(token, terminal, workflow.style(token))
                    },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { actions = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    tr("actions") + ": " + display(token),
                                    tint = mutedControlColor(),
                                )
                            }
                            DropdownMenu(actions, { actions = false }) {
                                DropdownMenuItem(
                                    text = { Text(tr("up")) },
                                    enabled = index > 0,
                                    onClick = {
                                        val reordered = tokens.toMutableList()
                                        reordered.add(index - 1, reordered.removeAt(index))
                                        actions = false
                                        applySequence(
                                            if (terminal) sequence.copy(terminal = reordered)
                                            else sequence.copy(process = reordered)
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(tr("down")) },
                                    enabled = index < tokens.lastIndex,
                                    onClick = {
                                        val reordered = tokens.toMutableList()
                                        reordered.add(index + 1, reordered.removeAt(index))
                                        actions = false
                                        applySequence(
                                            if (terminal) sequence.copy(terminal = reordered)
                                            else sequence.copy(process = reordered)
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            tr(
                                                if (terminal) "move_to_open_states"
                                                else "move_to_terminal_states"
                                            )
                                        )
                                    },
                                    enabled = tokens.size > 1,
                                    onClick = {
                                        actions = false
                                        applySequence(
                                            if (terminal)
                                                sequence.copy(
                                                    terminal = sequence.terminal - token,
                                                    process = sequence.process + token,
                                                )
                                            else
                                                sequence.copy(
                                                    process = sequence.process - token,
                                                    terminal = sequence.terminal + token,
                                                )
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(tr("remove")) },
                                    enabled = tokens.size > 1,
                                    onClick = {
                                        actions = false
                                        applySequence(
                                            if (terminal) sequence.copy(terminal = tokens - token)
                                            else sequence.copy(process = tokens - token)
                                        )
                                    },
                                )
                            }
                        }
                    },
                    modifier =
                        Modifier.clickable {
                            editingToken = token
                            editingTerminal = terminal
                            editingState = true
                        },
                )
            }
        }
        TextButton(
            onClick = {
                editingToken = null
                editingTerminal = terminal
                editingState = true
            }
        ) {
            Icon(Icons.Default.Add, null)
            Text(tr("add_state"))
        }
    }
    Choice(
        sequence.initial,
        sequence.process,
        "initial",
        onChange = { applySequence(sequence.copy(initial = it)) },
        labels = ::display,
    )
    Choice(
        sequence.complete,
        sequence.terminal,
        "complete_state",
        onChange = { applySequence(sequence.copy(complete = it)) },
        labels = ::display,
    )
    Choice(
        sequence.reopen,
        sequence.process,
        "reopen",
        onChange = { applySequence(sequence.copy(reopen = it)) },
        labels = ::display,
    )
    Choice(
        config.logging.done,
        WorkspaceConfig.logRules,
        "log_done",
        onChange = { change(config.copy(logging = config.logging.copy(done = it))) },
    )
    Choice(
        config.logging.reschedule,
        WorkspaceConfig.logRules,
        "log_reschedule",
        onChange = { change(config.copy(logging = config.logging.copy(reschedule = it))) },
    )
    Choice(
        config.logging.redeadline,
        WorkspaceConfig.logRules,
        "log_redeadline",
        onChange = { change(config.copy(logging = config.logging.copy(redeadline = it))) },
    )
    val drawerInvalid =
        config.logging.drawer.isNotEmpty() &&
            (!config.logging.drawer.matches(Regex("[\\p{L}\\p{N}\\p{M}_-]+")) ||
                config.logging.drawer.uppercase() in listOf("END", "PROPERTIES"))
    Field(
        config.logging.drawer,
        { change(config.copy(logging = config.logging.copy(drawer = it))) },
        "log_drawer",
        invalid = drawerInvalid,
        supporting = if (drawerInvalid) tr("invalid_log_drawer") else null,
    )
    if (editingState)
        key(editingToken, editingTerminal) {
            WorkflowStateEditor(
                config,
                workspaceId,
                editingToken,
                editingTerminal,
                { editingState = false },
                persist,
            )
        }
    pendingSequence?.let { candidate ->
        ModalPresence()
        AlertDialog(
            onDismissRequest = { pendingSequence = null },
            title = { Text(tr("repair_workflow_defaults")) },
            text = {
                val defaults = candidate.workflow.sequences.first()
                Text(
                    tr("repair_workflow_defaults_hint") +
                        "\n" +
                        tr("initial") +
                        ": " +
                        display(defaults.initial) +
                        "\n" +
                        tr("complete_state") +
                        ": " +
                        display(defaults.complete) +
                        "\n" +
                        tr("reopen") +
                        ": " +
                        display(defaults.reopen)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        change(candidate)
                        pendingSequence = null
                    }
                ) {
                    Text(tr("confirm"))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSequence = null }) { Text(tr("cancel")) }
            },
        )
    }
    if (preset) {
        ModalPresence()
        AlertDialog(
            onDismissRequest = { preset = false },
            title = { Text(tr("classic")) },
            text = { Text(tr("replace_workflow_hint")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        undo = config
                        change(config.copy(workflow = WorkspaceConfig.classic()))
                        preset = false
                    }
                ) {
                    Text(tr("confirm"))
                }
            },
            dismissButton = { TextButton(onClick = { preset = false }) { Text(tr("cancel")) } },
        )
    }
}

@Composable
private fun ConfigurationStateIcon(token: String, terminal: Boolean, style: KeywordStyle) {
    val icon =
        if (style.icon != "default") style.icon
        else
            when (token) {
                "NEXT" -> "arrow"
                "WAIT" -> "hourglass"
                "SOMEDAY" -> "moon"
                "URGENT" -> "exclamation"
                "CANCELED" -> "cross"
                else -> if (terminal) "check" else "circle"
            }
    val color =
        if (style.color != "default") style.color
        else
            when (token) {
                "NEXT" -> "blue"
                "WAIT" -> "orange"
                "SOMEDAY" -> "purple"
                "URGENT" -> "red"
                "CANCELED" -> "pink"
                else -> "gray"
            }
    WorkflowIcon(icon, workflowColor(color))
}

@Composable
internal fun WorkflowStateEditor(
    config: WorkspaceConfig,
    workspaceId: String,
    original: String?,
    terminal: Boolean,
    close: () -> Unit,
    persist: ConfigurationCommit,
) {
    ModalPresence()
    val baseline = rememberSaveable(saver = SettingsConfigurationSaver) { config }
    val initialWorkspace = rememberSaveable { workspaceId }
    var token by rememberSaveable { mutableStateOf(original.orEmpty()) }
    val originalStyle = baseline.workflow.style(original.orEmpty())
    var style by rememberSaveable(stateSaver = KeywordStyleSaver) { mutableStateOf(originalStyle) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val failureText = tr("save_failed")
    fun candidate(): WorkspaceConfig {
        val resolved = token.trim()
        val sequence = baseline.workflow.sequences.first()
        val updated =
            sequence.copy(
                process =
                    sequence.process +
                        if (original == null && !terminal) listOf(resolved) else emptyList(),
                terminal =
                    sequence.terminal +
                        if (original == null && terminal) listOf(resolved) else emptyList(),
            )
        return baseline
            .copy(
                workflow =
                    baseline.workflow.copy(
                        sequences = listOf(updated),
                        keywords =
                            baseline.workflow.keywords.filterKeys { it != original } +
                                (resolved to style),
                    )
            )
            .validate()
    }
    // An in-flight request resumes after recreation. The ViewModel treats an already persisted
    // candidate as success.
    LaunchedEffect(saving) {
        if (saving)
            try {
                persist(initialWorkspace, baseline, candidate())
                saving = false
                close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: failureText
                saving = false
            }
    }
    fun dismiss() {
        if (!saving) {
            if (token != original.orEmpty() || style != originalStyle) discard = true else close()
        }
    }
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { next ->
                if (next == SheetValue.Hidden && saving) false
                else if (
                    next == SheetValue.Hidden &&
                        (token != original.orEmpty() || style != originalStyle)
                ) {
                    discard = true
                    false
                } else true
            },
        )
    ModalBottomSheet(onDismissRequest = ::dismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("state"), style = MaterialTheme.typography.headlineSmall)
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            ListItem(
                headlineContent = { Text(style.label.ifBlank { token }.ifBlank { tr("state") }) },
                leadingContent = { ConfigurationStateIcon(token, terminal, style) },
            )
            if (original == null)
                Field(
                    token,
                    {
                        token = it
                        error = null
                    },
                    "state",
                    enabled = !saving,
                )
            else
                ListItem(
                    headlineContent = { Text(tr("state")) },
                    supportingContent = { Text(original) },
                )
            Field(style.label, { style = style.copy(label = it) }, "name", enabled = !saving)
            Field(style.key, { style = style.copy(key = it) }, "key", enabled = !saving)
            Choice(
                style.color,
                WorkspaceConfig.colors,
                "color",
                onChange = { style = style.copy(color = it) },
                enabled = !saving,
            )
            Choice(
                style.icon,
                WorkspaceConfig.icons,
                "icon",
                onChange = { style = style.copy(icon = it) },
                enabled = !saving,
            )
            Choice(
                style.log.enter,
                WorkspaceConfig.logRules,
                "log_enter",
                onChange = { style = style.copy(log = style.log.copy(enter = it)) },
                enabled = !saving,
            )
            Choice(
                style.log.leave,
                WorkspaceConfig.logRules,
                "log_leave",
                onChange = { style = style.copy(log = style.log.copy(leave = it)) },
                enabled = !saving,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = ::dismiss, enabled = !saving) { Text(tr("cancel")) }
                Button(
                    onClick = {
                        runCatching { candidate() }
                            .onSuccess {
                                error = null
                                saving = true
                            }
                            .onFailure { error = it.message ?: failureText }
                    },
                    enabled = !saving && token.isNotBlank(),
                ) {
                    Text(tr(if (saving) "saving" else if (error != null) "retry" else "save"))
                }
            }
        }
    }
    if (discard) DiscardDialog({ discard = false }, close)
}

@Composable
fun FileSettings(
    config: WorkspaceConfig,
    snapshot: WorkspaceSnapshot,
    change: (WorkspaceConfig) -> Unit,
) {
    val files = config.files
    val visible = snapshot.files.filter { !it.trashed }
    val orgPaths =
        visible.filter { !it.directory && it.path.endsWith(".org") }.map { it.path }.sorted()
    val directories =
        visible
            .flatMap { file ->
                val parts = file.path.split('/')
                (1 until parts.size).map { parts.take(it).joinToString("/") } +
                    if (file.directory) listOf(file.path) else emptyList()
            }
            .distinct()
            .sorted()
    var archiveSource by rememberSaveable { mutableStateOf<String?>(null) }
    var archiveAdvanced by rememberSaveable { mutableStateOf(false) }
    var matchesExpanded by rememberSaveable { mutableStateOf(false) }
    val example = archiveSource?.takeIf { it in orgPaths } ?: orgPaths.firstOrNull() ?: files.inbox
    SectionTitle("agenda_sources")
    ConfigurationPaths(
        config.agenda.sources,
        "included_paths",
        orgPaths + directories,
        directories.toSet(),
        "all_visible_org_files",
    ) {
        change(config.copy(agenda = config.agenda.copy(sources = it)))
    }
    ConfigurationPaths(
        config.agenda.excluded,
        "excluded_paths",
        orgPaths + directories,
        directories.toSet(),
        "none",
    ) {
        change(config.copy(agenda = config.agenda.copy(excluded = it)))
    }
    val matches = orgPaths.filter(config.agenda::includes)
    TextButton(onClick = { matchesExpanded = !matchesExpanded }) {
        Text(tr("matched_files") + ": ${matches.size}")
        Icon(if (matchesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
    }
    if (matchesExpanded) {
        if (matches.isEmpty()) Text(tr("no_matching_org_files"))
        else matches.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    SectionTitle("default_locations")
    WorkspacePathField(files.inbox, "inbox", snapshot) {
        change(config.copy(files = files.copy(inbox = it)))
    }
    WorkspacePathField(files.attachments, "attachments", snapshot, true) {
        change(config.copy(files = files.copy(attachments = it)))
    }
    WorkspacePathField(files.journal, "journal", snapshot, true) {
        change(config.copy(files = files.copy(journal = it)))
    }
    SectionTitle("archive")
    if (orgPaths.isNotEmpty())
        Choice(example, orgPaths, "example_source_file", { archiveSource = it }, labels = { it })
    val destination = runCatching {
        WorkspaceConfig.defaults().copy(files = FileRules(archive = files.archive)).validate()
        val parts = files.archive.split("::")
        val path =
            java.nio.file.Paths.get(example.substringBeforeLast('/', ""))
                .resolve(parts[0].trim().replace("%s", example.substringAfterLast('/')))
                .normalize()
                .toString()
        safePath(path)
        path to parts[1].trim()
    }
        .getOrNull()
    if (destination != null)
        SelectionContainer {
            Text(
                destination.first +
                    if (destination.second.isNotEmpty()) "\n${destination.second}" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    if (files.archive != WorkspaceConfig.defaults().files.archive)
        TextButton(
            onClick = {
                change(
                    config.copy(
                        files = files.copy(archive = WorkspaceConfig.defaults().files.archive)
                    )
                )
            }
        ) {
            Text(tr("archive_beside_source"))
        }
    TextButton(onClick = { archiveAdvanced = !archiveAdvanced }) {
        Text(tr("archive_rule"))
        Icon(if (archiveAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
    }
    if (archiveAdvanced || destination == null)
        Field(
            files.archive,
            { change(config.copy(files = files.copy(archive = it))) },
            "archive_rule",
            invalid = destination == null,
        )
    SectionTitle("refile")
    ConfigurationPaths(
        files.refile,
        "target_files",
        orgPaths,
        emptySet(),
        "no_refile_targets",
        requiresOrg = true,
    ) {
        change(config.copy(files = files.copy(refile = it)))
    }
    NumberField(files.refileMaxLevel, "max_level") {
        change(config.copy(files = files.copy(refileMaxLevel = it)))
    }
}

@Composable
private fun ConfigurationPaths(
    paths: List<String>,
    label: String,
    choices: List<String>,
    directories: Set<String>,
    empty: String,
    requiresOrg: Boolean = false,
    change: (List<String>) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(tr(label)) },
        supportingContent = { Text(if (paths.isEmpty()) tr(empty) else paths.joinToString(", ")) },
        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        modifier = Modifier.clickable { choosing = true },
    )
    if (choosing) {
        ModalPresence()
        var newPath by rememberSaveable { mutableStateOf("") }
        val valid = runCatching {
            if (requiresOrg) WorkspaceConfig.orgPath(newPath)
            else WorkspaceConfig.configPath(newPath)
        }
            .isSuccess
        ModalBottomSheet(
            onDismissRequest = { choosing = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tr(label), style = MaterialTheme.typography.headlineSmall)
                if (paths.isEmpty()) Text(tr(empty), style = MaterialTheme.typography.bodySmall)
                else TextButton(onClick = { change(emptyList()) }) { Text(tr("clear")) }
                (choices + paths).distinct().sorted().forEach { path ->
                    ListItem(
                        headlineContent = { Text(path) },
                        leadingContent = {
                            Icon(
                                if (path in directories) Icons.Default.Folder
                                else Icons.Default.Description,
                                null,
                            )
                        },
                        trailingContent = { Checkbox(path in paths, null) },
                        modifier =
                            Modifier.toggleable(value = path in paths, role = Role.Checkbox) {
                                selected ->
                                change(if (selected) paths + path else paths - path)
                            },
                    )
                }
                Field(
                    newPath,
                    { newPath = it },
                    "path",
                    invalid = newPath.isNotEmpty() && !valid,
                    supporting =
                        if (newPath.isNotEmpty() && !valid) tr("invalid_destination") else null,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { choosing = false }) { Text(tr("close")) }
                    Button(
                        onClick = {
                            if (newPath !in paths) change(paths + newPath)
                            newPath = ""
                        },
                        enabled = valid,
                    ) {
                        Text(tr("add"))
                    }
                }
            }
        }
    }
}

@Composable
fun CaptureSettings(
    config: WorkspaceConfig,
    snapshot: WorkspaceSnapshot,
    previewTemplate: (CaptureTemplate) -> Unit = {},
    persist: ConfigurationCommit,
    change: (WorkspaceConfig) -> Unit,
) {
    val capture = config.capture
    val templates = capture.templates
    var editing by
        rememberSaveable(stateSaver = SettingsTemplateSaver) {
            mutableStateOf<CaptureTemplate?>(null)
        }
    var removing by
        rememberSaveable(stateSaver = SettingsTemplateSaver) {
            mutableStateOf<CaptureTemplate?>(null)
        }
    Choice(
        capture.defaultTemplate,
        templates.map { it.id },
        "default_template",
        onChange = { change(config.copy(capture = capture.copy(defaultTemplate = it))) },
        labels = { id -> templates.first { it.id == id }.name },
    )
    templates.forEachIndexed { index, entry ->
        key(entry.id) {
            var actions by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text(entry.name) },
                supportingContent = {
                    Text(entry.target.path + if (entry.key.isNotEmpty()) " · ${entry.key}" else "")
                },
                leadingContent = { Icon(Icons.Default.Description, null) },
                trailingContent = {
                    Box {
                        IconButton(onClick = { actions = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                tr("actions") + ": " + entry.name,
                                tint = mutedControlColor(),
                            )
                        }
                        DropdownMenu(actions, { actions = false }) {
                            DropdownMenuItem(
                                text = { Text(tr("up")) },
                                enabled = index > 0,
                                onClick = {
                                    val reordered = templates.toMutableList()
                                    reordered.add(index - 1, reordered.removeAt(index))
                                    change(
                                        config.copy(capture = capture.copy(templates = reordered))
                                    )
                                    actions = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("down")) },
                                enabled = index < templates.lastIndex,
                                onClick = {
                                    val reordered = templates.toMutableList()
                                    reordered.add(index + 1, reordered.removeAt(index))
                                    change(
                                        config.copy(capture = capture.copy(templates = reordered))
                                    )
                                    actions = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("remove")) },
                                enabled = templates.size > 1,
                                onClick = {
                                    removing = entry
                                    actions = false
                                },
                            )
                        }
                    }
                },
                modifier = Modifier.clickable { editing = entry },
            )
        }
    }
    OutlinedButton(
        onClick = {
            editing =
                CaptureTemplate(
                    id = UUID.randomUUID().toString(),
                    name = "",
                    key = "",
                    target = CaptureTarget(path = config.files.inbox),
                )
        }
    ) {
        Icon(Icons.Default.Add, null)
        Text(tr("add"))
    }
    editing?.let { template ->
        key(template.id) {
            CaptureTemplateEditor(
                config,
                snapshot,
                template,
                { editing = null },
                previewTemplate,
                persist,
            )
        }
    }
    removing?.let { template ->
        ModalPresence()
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(tr("remove")) },
            text = { Text(template.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val remaining = templates.filter { it.id != template.id }
                        if (remaining.isNotEmpty())
                            change(
                                config.copy(
                                    capture =
                                        capture.copy(
                                            templates = remaining,
                                            defaultTemplate =
                                                if (capture.defaultTemplate == template.id)
                                                    remaining.first().id
                                                else capture.defaultTemplate,
                                        )
                                )
                            )
                        removing = null
                    }
                ) {
                    Text(tr("remove"))
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(tr("cancel")) } },
        )
    }
}

@Composable
internal fun CaptureTemplateEditor(
    config: WorkspaceConfig,
    snapshot: WorkspaceSnapshot,
    original: CaptureTemplate,
    close: () -> Unit,
    preview: (CaptureTemplate) -> Unit,
    persist: ConfigurationCommit,
) {
    ModalPresence()
    val baseline = rememberSaveable(saver = SettingsConfigurationSaver) { config }
    val initialWorkspace = rememberSaveable { snapshot.workspace?.id.orEmpty() }
    var template by rememberSaveable(stateSaver = TemplateDraftSaver) { mutableStateOf(original) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var syntax by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val failureText = tr("save_failed")
    fun candidate(): WorkspaceConfig {
        val templates = baseline.capture.templates
        val updated =
            if (templates.any { it.id == template.id })
                templates.map { if (it.id == template.id) template else it }
            else templates + template
        return baseline.copy(capture = baseline.capture.copy(templates = updated)).validate()
    }
    LaunchedEffect(saving) {
        if (saving)
            try {
                persist(initialWorkspace, baseline, candidate())
                saving = false
                close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: failureText
                saving = false
            }
    }
    fun dismiss() {
        if (!saving) {
            if (template != original) discard = true else close()
        }
    }
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { next ->
                if (next == SheetValue.Hidden && saving) false
                else if (next == SheetValue.Hidden && template != original) {
                    discard = true
                    false
                } else true
            },
        )
    ModalBottomSheet(onDismissRequest = ::dismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("template"), style = MaterialTheme.typography.headlineSmall)
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            Field(template.name, { template = template.copy(name = it) }, "name", enabled = !saving)
            Choice(
                template.type,
                listOf("entry", "item", "checkitem", "plain"),
                "template_type",
                onChange = { template = template.copy(type = it) },
                enabled = !saving,
                presentation = ChoicePresentation.Chips,
            )
            Choice(
                template.target.type,
                listOf("file", "headline", "outline", "datetree"),
                "target_type",
                onChange = { template = template.copy(target = template.target.copy(type = it)) },
                enabled = !saving,
                presentation = ChoicePresentation.Chips,
            )
            WorkspacePathField(template.target.path, "path", snapshot, enabled = !saving) {
                template = template.copy(target = template.target.copy(path = it))
            }
            if (template.target.type in listOf("headline", "outline"))
                ValuesField(template.target.outline, "target_outline", enabled = !saving) {
                    template = template.copy(target = template.target.copy(outline = it))
                }
            Field(
                template.template,
                { template = template.copy(template = it) },
                "template_source",
                singleLine = false,
                enabled = !saving,
            )
            TextButton(onClick = { syntax = !syntax }, enabled = !saving) {
                Text(tr("template_syntax"))
            }
            if (syntax)
                SelectionContainer {
                    Text(tr("template_syntax_hint"), style = MaterialTheme.typography.bodySmall)
                }
            OutlinedButton(
                onClick = {
                    runCatching { candidate() }
                        .onSuccess {
                            error = null
                            preview(template)
                        }
                        .onFailure { error = it.message }
                },
                enabled = !saving,
            ) {
                Text(tr("try_template"))
            }
            TextButton(onClick = { advanced = !advanced }, enabled = !saving) {
                Text(tr("advanced"))
                Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (advanced) {
                Field(
                    template.group,
                    { template = template.copy(group = it) },
                    "group",
                    enabled = !saving,
                )
                Field(
                    template.key,
                    { template = template.copy(key = it) },
                    "key",
                    enabled = !saving,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("prepend"), Modifier.weight(1f))
                    Switch(
                        template.prepend,
                        { template = template.copy(prepend = it) },
                        enabled = !saving,
                    )
                }
                NumberField(template.emptyLines, "empty_lines", enabled = !saving) {
                    template = template.copy(emptyLines = it)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = ::dismiss, enabled = !saving) { Text(tr("cancel")) }
                Button(
                    onClick = {
                        runCatching { candidate() }
                            .onSuccess {
                                error = null
                                saving = true
                            }
                            .onFailure { error = it.message ?: failureText }
                    },
                    enabled =
                        !saving && template.name.isNotBlank() && template.template.isNotBlank(),
                ) {
                    Text(tr(if (saving) "saving" else if (error != null) "retry" else "save"))
                }
            }
        }
    }
    if (discard) DiscardDialog({ discard = false }, close)
}
