@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roifewu.orgenda.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.*
import java.time.*
import kotlinx.coroutines.*

private data class CapturePreview(val text: String, val baseline: String?)

private val CaptureAnswersSaver =
    listSaver<Map<Int, String>, Any>(
        save = { answers -> answers.flatMap { listOf(it.key, it.value) } },
        restore = { values ->
            values.chunked(2).associate { (key, value) -> key as Int to value as String }
        },
    )

@Composable
fun CaptureSheet(
    model: OrgendaViewModel,
    initialDate: LocalDate? = null,
    initialTemplate: CaptureTemplate? = null,
    previewOnly: Boolean = false,
    close: () -> Unit,
) {
    ModalPresence()
    val snapshot = remember { model.workspace.value }
    val initialWorkspace by rememberSaveable { mutableStateOf(snapshot.workspace?.id) }
    val configurationSource = rememberSaveable { ConfigurationCodec.encode(snapshot.configuration) }
    val config = remember(configurationSource) { ConfigurationCodec.decode(configurationSource) }
    var selected by rememberSaveable {
        mutableStateOf(initialTemplate?.id ?: config.capture.defaultTemplate)
    }
    val templates = initialTemplate?.let { listOf(it) } ?: config.capture.templates
    val template = templates.first { it.id == selected }
    val prompts =
        remember(selected) {
            CaptureEngine.expansions(template.template).filter { it.prompt != null }
        }
    var answers by
        rememberSaveable(selected, stateSaver = CaptureAnswersSaver) {
            mutableStateOf(prompts.associate { it.start to (it.choices.firstOrNull() ?: "") })
        }
    var content by rememberSaveable { mutableStateOf("") }
    var createMissing by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var now by
        rememberSaveable(stateSaver = LocalDateTimeSaver) {
            mutableStateOf(
                (initialDate ?: LocalDate.now()).atTime(LocalTime.now().withSecond(0).withNano(0))
            )
        }
    var link by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable { mutableStateOf("") }
    var clipboardText by rememberSaveable { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var preview by
        remember(selected, answers, content, now, link, selection, clipboardText, createMissing) {
            mutableStateOf<CapturePreview?>(null)
        }
    val scope = rememberCoroutineScope()
    val dirty =
        content.isNotEmpty() ||
            link.isNotEmpty() ||
            selection.isNotEmpty() ||
            clipboardText.isNotEmpty() ||
            prompts.any { prompt ->
                prompt.token.last() !in "tTuU" &&
                    answers.getValue(prompt.start) != prompt.choices.firstOrNull().orEmpty()
            }
    fun dismiss() {
        if (!saving) {
            if (dirty) confirmDiscard = true else close()
        }
    }
    fun render() {
        runCatching {
            require(
                model.workspace.value.workspace?.id == initialWorkspace &&
                    model.workspace.value.configuration == config
            ) {
                "Workspace or configuration changed; reopen capture"
            }
            CapturePreview(
                CaptureEngine.render(
                    template.template,
                    answers,
                    content,
                    now,
                    link = link,
                    selection = selection,
                    clipboard = clipboardText,
                ),
                model.workspace.value.files
                    .firstOrNull { it.path == template.target.path }
                    ?.contentBlob,
            )
        }
            .onSuccess {
                preview = it
                error = null
            }
            .onFailure {
                preview = null
                error = it.message
            }
    }
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { next ->
                if (next == SheetValue.Hidden && (saving || dirty)) {
                    if (!saving) confirmDiscard = true
                    false
                } else true
            },
        )
    ModalBottomSheet(onDismissRequest = ::dismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("capture"), style = MaterialTheme.typography.headlineSmall)
            if (templates.size > 1)
                Choice(selected, templates.map { it.id }, "template", { selected = it }) { id ->
                    templates
                        .first { it.id == id }
                        .let {
                            listOf(it.group, it.name).filter(String::isNotEmpty).joinToString(" / ")
                        }
                }
            PlanningField(
                "capture_date",
                PlanningValue(true, now, true, ""),
                allowDisable = false,
                showRepeat = false,
                showTimeToggle = false,
            ) {
                now = it.date
            }
            prompts.forEach { prompt ->
                if (prompt.token.last() in "tTuU") {
                    val suffix = prompt.token.last()
                    var date by
                        rememberSaveable(selected, prompt.start, stateSaver = LocalDateTimeSaver) {
                            mutableStateOf(now)
                        }
                    LaunchedEffect(date) {
                        answers =
                            answers +
                                (prompt.start to orgTimestamp(date, suffix in "tT", suffix in "TU"))
                    }
                    Text(prompt.prompt!!)
                    PlanningField(
                        "date",
                        PlanningValue(true, date, suffix in "TU", ""),
                        allowDisable = false,
                        showRepeat = false,
                        showTimeToggle = false,
                    ) {
                        date = it.date
                    }
                } else
                    Field(
                        answers.getValue(prompt.start),
                        { answers = answers + (prompt.start to it) },
                        prompt.prompt!!,
                        singleLine = false,
                    )
                if (prompt.choices.isNotEmpty())
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        prompt.choices.forEach { value ->
                            SuggestionChip(
                                onClick = { answers = answers + (prompt.start to value) },
                                label = { Text(value) },
                            )
                        }
                    }
            }
            if (template.template.contains("%?"))
                Field(content, { content = it }, "notes", singleLine = false)
            if (template.template.contains("%a")) Field(link, { link = it }, "context_link")
            if (template.template.contains("%i"))
                Field(selection, { selection = it }, "selected_text", singleLine = false)
            if (template.template.contains("%x")) {
                Field(clipboardText, { clipboardText = it }, "clipboard_text", singleLine = false)
                TextButton(onClick = { clipboardText = clipboard.getText()?.text.orEmpty() }) {
                    Text(tr("paste"))
                }
            }
            Text(
                template.target.path +
                    if (template.target.outline.isNotEmpty())
                        " :: " + template.target.outline.joinToString(" / ")
                    else "",
                style = MaterialTheme.typography.bodySmall,
            )
            if (template.target.outline.isNotEmpty() || template.target.type == "datetree")
                Row {
                    Text(tr("create_missing"), Modifier.weight(1f))
                    Switch(createMissing, { createMissing = it })
                }
            OutlinedButton(onClick = ::render, enabled = !saving) { Text(tr("preview")) }
            preview?.let { generated ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    SelectionContainer {
                        Text(
                            generated.text,
                            Modifier.fillMaxWidth().padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
                ?: Text(
                    tr("preview_before_capture"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            error?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = ::dismiss, enabled = !saving) { Text(tr("cancel")) }
                if (!previewOnly)
                    Button(
                        enabled = !saving && preview?.text?.isNotBlank() == true,
                        onClick = {
                            val generated = preview ?: return@Button
                            saving = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        model.repository.capture(
                                            template,
                                            generated.text,
                                            generated.baseline,
                                            ConfigurationCodec.encode(config).sha256(),
                                            requireNotNull(initialWorkspace),
                                            createMissing,
                                            now,
                                        )
                                    }
                                    com.roifewu.orgenda.platform.SyncWorker.enqueue(
                                        model.app,
                                        initialWorkspace,
                                    )
                                    close()
                                } catch (e: Exception) {
                                    error = e.message
                                    saving = false
                                }
                            }
                        },
                    ) {
                        Text(tr("insert"))
                    }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDiscard) DiscardDialog({ confirmDiscard = false }, close)
}

@Composable
fun JournalSheet(day: LocalDate, close: () -> Unit, save: suspend (String, String) -> Unit) {
    ModalPresence()
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun dismiss() {
        if (!saving) {
            if (title.isNotEmpty() || body.isNotEmpty()) confirmDiscard = true else close()
        }
    }
    val scope = rememberCoroutineScope()
    val bodyFocus = remember { FocusRequester() }
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { next ->
                if (
                    next == SheetValue.Hidden && (saving || title.isNotEmpty() || body.isNotEmpty())
                ) {
                    if (!saving) confirmDiscard = true
                    false
                } else true
            },
        )
    ModalBottomSheet(onDismissRequest = ::dismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(day.toString(), style = MaterialTheme.typography.headlineSmall)
            Field(
                title,
                { title = it },
                "optional_title",
                modifier = Modifier.testTag("journal.title"),
            )
            Field(
                body,
                { body = it },
                "notes",
                modifier =
                    Modifier.heightIn(min = 220.dp)
                        .focusRequester(bodyFocus)
                        .testTag("journal.body"),
                singleLine = false,
            )
            LaunchedEffect(Unit) { bodyFocus.requestFocus() }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = ::dismiss, enabled = !saving) { Text(tr("cancel")) }
                Button(
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { save(title.trim(), body) }
                                close()
                            } catch (e: Exception) {
                                error = e.message
                                saving = false
                            }
                        }
                    },
                    enabled = body.isNotBlank() && !saving,
                ) {
                    Text(tr("save"))
                }
            }
        }
    }
    if (confirmDiscard) DiscardDialog({ confirmDiscard = false }, close)
}
