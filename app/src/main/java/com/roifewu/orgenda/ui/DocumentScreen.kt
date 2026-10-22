@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roifewu.orgenda.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.roifewu.orgenda.*
import com.roifewu.orgenda.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DocumentScreen(
    model: OrgendaViewModel,
    editor: EditorState,
    openItem: (OrgHeading) -> Unit,
    close: () -> Unit,
    complete: (OrgHeading) -> Unit,
) {
    var insertDate by rememberSaveable(editor.session) { mutableStateOf(false) }
    var dateSelectionStart by rememberSaveable(editor.session) { mutableStateOf(0) }
    var dateSelectionEnd by rememberSaveable(editor.session) { mutableStateOf(0) }
    var dateSourceHash by rememberSaveable(editor.session) { mutableStateOf("") }
    var outline by rememberSaveable(editor.session) { mutableStateOf(false) }
    var native by remember { mutableStateOf<NativeSourceEditor?>(null) }
    var handledSearch by rememberSaveable(editor.session) { mutableStateOf("") }
    var resumeKeyboard by rememberSaveable(editor.session) { mutableStateOf(false) }
    var handledFocus by rememberSaveable(editor.session) { mutableStateOf(editor.focusRevision) }
    val history =
        remember(editor.session) {
            model.editorHistories.getOrPut(editor.session) { EditorHistory() }
        }
    val modeState = key(editor.session) { rememberSaveableStateHolder() }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val codeAccent = MaterialTheme.colorScheme.tertiary.toArgb()
    val workflow =
        editor.document?.workflow ?: model.workspace.collectAsState().value.configuration.workflow
    val syntaxColors = buildMap {
        put("muted", MaterialTheme.colorScheme.onSurfaceVariant.toArgb())
        put("secondary", MaterialTheme.colorScheme.secondary.toArgb())
        put("surface", MaterialTheme.colorScheme.surfaceContainer.toArgb())
        put("tag", MaterialTheme.colorScheme.secondaryContainer.toArgb())
        for (name in listOf("red", "orange", "blue", "green")) put(
            name,
            workflowColor(name).toArgb(),
        )
        for (token in workflow.tokens) put("state:$token", workflowColor(workflow, token).toArgb())
    }
    val context = LocalContext.current
    val pageActive by rememberUpdatedState(LocalPageActive.current)
    fun suspendEditor() {
        native?.let { view ->
            view.suspendInput()
            view.captureReadingPosition()
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
    }
    fun switchMode(preview: Boolean) {
        if (preview != editor.preview) {
            if (preview)
                resumeKeyboard =
                    native?.let {
                        it.hasFocus() &&
                            androidx.core.view.ViewCompat.getRootWindowInsets(it)
                                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) ==
                                true
                    } == true
            suspendEditor()
            model.preview(preview)
        }
    }
    fun closeDate() {
        insertDate = false
        native?.resumeKeyboard()
    }
    LaunchedEffect(native, editor.preview, editor.focusRevision, pageActive) {
        if (!pageActive) {
            native?.suspendInput()
            native?.clearFocus()
        } else if (
            !editor.preview &&
                native != null &&
                (resumeKeyboard || handledFocus != editor.focusRevision)
        ) {
            resumeKeyboard = false
            handledFocus = editor.focusRevision
            native?.resumeKeyboard()
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        editor.file.path.substringAfterLast('/'),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        tr(
                            if (editor.saving) "saving"
                            else if (editor.dirty) "unsaved"
                            else if (editor.file.draftBlob != null) "saved" else "synced"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = {
                        suspendEditor()
                        close()
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("back"))
                }
            },
            actions = {
                if (androidx.compose.ui.platform.LocalConfiguration.current.fontScale >= 1.5f) {
                    var modes by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { modes = true }) {
                            Text(tr(if (editor.preview) "preview" else "edit"))
                            Icon(Icons.Default.ArrowDropDown, null, tint = mutedControlColor())
                        }
                        DropdownMenu(modes, { modes = false }) {
                            DropdownMenuItem(
                                text = { Text(tr("edit")) },
                                onClick = {
                                    modes = false
                                    switchMode(false)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("preview")) },
                                onClick = {
                                    modes = false
                                    switchMode(true)
                                },
                            )
                        }
                    }
                } else
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            !editor.preview,
                            { switchMode(false) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            icon = {},
                        ) {
                            Text(tr("edit"), maxLines = 1)
                        }
                        SegmentedButton(
                            editor.preview,
                            { switchMode(true) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            icon = {},
                        ) {
                            Text(tr("preview"), maxLines = 1)
                        }
                    }
                IconButton(
                    onClick = {
                        suspendEditor()
                        model.action {
                            model.saveNow()
                            withContext(Dispatchers.Main) { outline = true }
                        }
                    },
                    enabled = editor.document != null && !editor.saving,
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, tr("outline"))
                }
            },
        )
        editor.error?.let { message ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    message + "\n" + tr("save_failed"),
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { model.flush() }, enabled = !editor.saving) {
                    Text(tr("retry"))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (editor.preview)
                modeState.SaveableStateProvider("preview") {
                    OrgPreview(model, editor, openItem, complete)
                }
            else
                modeState.SaveableStateProvider("edit") {
                    key(editor.session) {
                        var appliedJumpByte by rememberSaveable { mutableStateOf<Int?>(null) }
                        var appliedRevision by rememberSaveable { mutableStateOf<Long?>(null) }
                        AndroidView(
                            factory = { ctx ->
                                NativeSourceEditor(ctx, history).also { view ->
                                    val restoring =
                                        appliedJumpByte == editor.jumpByte &&
                                            appliedRevision == editor.navigationRevision
                                    view.display(
                                        editor.text,
                                        if (restoring) editor.readingByte else editor.jumpByte,
                                    )
                                    view.changed = { source, byte ->
                                        if (
                                            pageActive &&
                                                model.editor.value?.session == editor.session
                                        )
                                            model.edit(source, byte)
                                    }
                                    view.positionChanged = { byte ->
                                        if (
                                            pageActive &&
                                                model.editor.value?.session == editor.session
                                        )
                                            model.reading(byte)
                                    }
                                    native = view
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            update = { view ->
                                view.setTextColor(textColor)
                                view.accent = accent
                                view.codeAccent = codeAccent
                                view.syntaxColors = syntaxColors
                                // Never replace the buffer during composing input or a pending
                                // local save.
                                if (
                                    !editor.dirty &&
                                        !editor.saving &&
                                        view.text.toString() != editor.text
                                )
                                    view.display(editor.text)
                                if (
                                    appliedJumpByte != editor.jumpByte ||
                                        appliedRevision != editor.navigationRevision
                                ) {
                                    appliedJumpByte = editor.jumpByte
                                    appliedRevision = editor.navigationRevision
                                    view.display(view.text.toString(), editor.jumpByte)
                                }
                                if (
                                    editor.searchQuery.isNotEmpty() &&
                                        handledSearch != editor.searchQuery &&
                                        view.selectSearchMatch(editor.searchQuery)
                                ) {
                                    handledSearch = editor.searchQuery
                                    view.resumeKeyboard()
                                }
                            },
                            onRelease = {
                                it.positionChanged = null
                                it.dispose()
                                native = null
                            },
                        )
                    }
                }
        }
        if (!editor.preview)
            Row(
                Modifier.fillMaxWidth().imePadding(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = { native?.command(InputCommand.HEADING) }) {
                    Icon(Icons.Default.Title, tr("heading"))
                }
                IconButton(onClick = { native?.command(InputCommand.CHECKBOX) }) {
                    Icon(Icons.Default.CheckBox, tr("checkbox"))
                }
                IconButton(onClick = { native?.command(InputCommand.INDENT) }) {
                    Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, tr("indent"))
                }
                IconButton(onClick = { native?.command(InputCommand.OUTDENT) }) {
                    Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, tr("outdent"))
                }
                IconButton(
                    onClick = {
                        native?.let { view ->
                            view.suspendInput()
                            dateSelectionStart = view.selectionStart.coerceAtLeast(0)
                            dateSelectionEnd = view.selectionEnd.coerceAtLeast(0)
                            dateSourceHash = view.text.toString().sha256()
                            insertDate = true
                        }
                    }
                ) {
                    Icon(Icons.Default.CalendarMonth, tr("date"))
                }
                IconButton(onClick = { suspendEditor() }) {
                    Icon(Icons.Default.KeyboardHide, tr("hide_keyboard"))
                }
            }
    }
    if (insertDate) {
        ModalPresence()
        val dateState =
            rememberDatePickerState(
                java.time.LocalDate.now()
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            )
        val sourceUnchanged = editor.text.sha256() == dateSourceHash
        DatePickerDialog(
            onDismissRequest = ::closeDate,
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { millis ->
                            native
                                ?.takeIf { it.text.toString().sha256() == dateSourceHash }
                                ?.insert(
                                    orgTimestamp(
                                        java.time.Instant.ofEpochMilli(millis)
                                            .atZone(java.time.ZoneOffset.UTC)
                                            .toLocalDateTime(),
                                        includesTime = false,
                                    ),
                                    dateSelectionStart,
                                    dateSelectionEnd,
                                )
                        }
                        closeDate()
                    },
                    enabled = sourceUnchanged && dateState.selectedDateMillis != null,
                ) {
                    Text(tr("insert"))
                }
            },
            dismissButton = { TextButton(onClick = ::closeDate) { Text(tr("cancel")) } },
        ) {
            Column {
                if (!sourceUnchanged)
                    Text(
                        tr("date_source_changed"),
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                DatePicker(dateState)
            }
        }
    }
    if (outline) OutlineSheet(model, editor) { outline = false }
}
