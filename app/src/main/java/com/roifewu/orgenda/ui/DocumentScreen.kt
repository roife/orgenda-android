@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.roifewu.orgenda.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.roifewu.orgenda.*
import com.roifewu.orgenda.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun DocumentScreen(model: OrgendaViewModel, editor: EditorState, openItem: (OrgHeading) -> Unit, close: () -> Unit, complete: (OrgHeading) -> Unit) {
    var insertDate by rememberSaveable(editor.session) { mutableStateOf(false) }
    var dateSelectionStart by rememberSaveable(editor.session) { mutableStateOf(0) }
    var dateSelectionEnd by rememberSaveable(editor.session) { mutableStateOf(0) }
    var dateSourceHash by rememberSaveable(editor.session) { mutableStateOf("") }
    var outline by rememberSaveable(editor.session) { mutableStateOf(false) }
    var native by remember { mutableStateOf<NativeSourceEditor?>(null) }
    var canUndo by remember(editor.session) { mutableStateOf(false) }
    var canRedo by remember(editor.session) { mutableStateOf(false) }
    var handledSearch by rememberSaveable(editor.session) { mutableStateOf("") }
    val history = remember(editor.session) { model.editorHistories.getOrPut(editor.session) { EditorHistory() } }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val codeAccent = MaterialTheme.colorScheme.tertiary.toArgb()
    val context = LocalContext.current
    fun suspendEditor() {
        native?.let { view ->
            view.finishTyping()
            view.captureReadingPosition()
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
    }
    fun switchMode(preview: Boolean) { if (preview != editor.preview) { suspendEditor(); model.preview(preview) } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) model.action {
        context.contentResolver.openOutputStream(uri)?.use { it.write(editor.text.utf8Bytes()) } ?: error("Export destination unavailable")
    } }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Column {
            Text(editor.file.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr(if (editor.saving) "saving" else if (editor.dirty) "unsaved" else if (editor.file.draftBlob != null) "saved" else "synced"), style = MaterialTheme.typography.labelSmall)
        } }, navigationIcon = { IconButton(onClick = { suspendEditor(); close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("back")) } }, actions = {
            IconButton(onClick = { export.launch(editor.file.path.substringAfterLast('/')) }) { Icon(Icons.Default.FileDownload, tr("export_document")) }
            IconButton(onClick = {
                suspendEditor()
                model.action { model.saveNow(); withContext(Dispatchers.Main) { outline = true } }
            }, enabled = editor.document != null && !editor.saving) { Icon(Icons.AutoMirrored.Filled.List, tr("outline")) }
        })
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(editor.preview, { switchMode(true) }, label = { Text(tr("preview")) })
            FilterChip(!editor.preview, { switchMode(false) }, label = { Text(tr("edit")) })
            Spacer(Modifier.weight(1f))
            if (!editor.preview) {
                IconButton(onClick = { native?.undo() }, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, tr("undo")) }
                IconButton(onClick = { native?.redo() }, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, tr("redo")) }
            }
        }
        editor.error?.let { message -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(message + "\n" + tr("save_failed"), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { model.flush() }, enabled = !editor.saving) { Text(tr("retry")) }
        } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (editor.preview) OrgPreview(model, editor, openItem, complete)
            else key(editor.session) {
                var appliedJumpByte by rememberSaveable { mutableStateOf<Int?>(null) }
                var appliedRevision by rememberSaveable { mutableStateOf<Long?>(null) }
                AndroidView(factory = { ctx -> NativeSourceEditor(ctx, history).also { view ->
                    val restoring = appliedJumpByte == editor.jumpByte && appliedRevision == editor.navigationRevision
                    view.display(editor.text, if (restoring) editor.readingByte else editor.jumpByte)
                    view.changed = { source, byte -> model.edit(source, byte) }
                    view.positionChanged = model::reading
                    view.historyChanged = { undo, redo -> canUndo = undo; canRedo = redo }
                    canUndo = view.canUndo; canRedo = view.canRedo
                    native = view
                } }, modifier = Modifier.fillMaxSize(), update = { view ->
                    view.setTextColor(textColor); view.accent = accent; view.codeAccent = codeAccent
                    // Never replace the buffer during composing input or a pending local save.
                    if (!editor.dirty && !editor.saving && view.text.toString() != editor.text) view.display(editor.text)
                    if (appliedJumpByte != editor.jumpByte || appliedRevision != editor.navigationRevision) {
                        appliedJumpByte = editor.jumpByte; appliedRevision = editor.navigationRevision
                        view.display(view.text.toString(), editor.jumpByte)
                    }
                    if (editor.searchQuery.isNotEmpty() && handledSearch != editor.searchQuery && view.selectSearchMatch(editor.searchQuery)) handledSearch = editor.searchQuery
                }, onRelease = { it.historyChanged = null; it.positionChanged = null; it.dispose(); native = null })
            }
        }
        if (!editor.preview) Row(Modifier.fillMaxWidth().imePadding(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { native?.command(InputCommand.HEADING) }) { Icon(Icons.Default.Title, tr("heading")) }
            IconButton(onClick = { native?.command(InputCommand.CHECKBOX) }) { Icon(Icons.Default.CheckBox, tr("checkbox")) }
            IconButton(onClick = { native?.command(InputCommand.INDENT) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, tr("indent")) }
            IconButton(onClick = { native?.command(InputCommand.OUTDENT) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, tr("outdent")) }
            IconButton(onClick = { native?.let { view ->
                view.finishTyping()
                dateSelectionStart = view.selectionStart.coerceAtLeast(0)
                dateSelectionEnd = view.selectionEnd.coerceAtLeast(0)
                dateSourceHash = view.text.toString().sha256()
                insertDate = true
            } }) { Icon(Icons.Default.CalendarMonth, tr("date")) }
            IconButton(onClick = { suspendEditor() }) { Icon(Icons.Default.KeyboardHide, tr("hide_keyboard")) }
        }
    }
    if (insertDate) {
        ModalPresence()
        val dateState = rememberDatePickerState(java.time.LocalDate.now().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        val sourceUnchanged = editor.text.sha256() == dateSourceHash
        DatePickerDialog(onDismissRequest = { insertDate = false }, confirmButton = { TextButton(onClick = {
            dateState.selectedDateMillis?.let { millis -> native?.takeIf { it.text.toString().sha256() == dateSourceHash }?.insert(orgTimestamp(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDateTime(), includesTime = false), dateSelectionStart, dateSelectionEnd) }; insertDate = false
        }, enabled = sourceUnchanged && dateState.selectedDateMillis != null) { Text(tr("insert")) } }, dismissButton = { TextButton(onClick = { insertDate = false }) { Text(tr("cancel")) } }) {
            Column { if (!sourceUnchanged) Text(tr("date_source_changed"), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error); DatePicker(dateState) }
        }
    }
    if (outline) OutlineSheet(model, editor) { outline = false }
}
