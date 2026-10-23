@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roifewu.orgenda.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.*
import kotlinx.coroutines.*

private val PlanningDraftSaver =
    listSaver<PlanningDraft, Any>(
        save = { draft ->
            listOf(draft.original, draft.keyword.orEmpty()) +
                draft.timestamps.flatMap { listOf(it.date.toString(), it.time, it.recurrence) }
        },
        restore = { values ->
            PlanningDraft.parse(values[0] as String)?.let { original ->
                original.copy(
                    keyword = (values[1] as String).ifEmpty { null },
                    timestamps =
                        original.timestamps.mapIndexed { index, stamp ->
                            stamp.copy(
                                date =
                                    java.time.LocalDateTime.parse(values[2 + index * 3] as String),
                                time = values[3 + index * 3] as Boolean,
                                recurrence = values[4 + index * 3] as String,
                            )
                        },
                )
            }
        },
    )

@Composable
fun TimestampSheet(
    model: OrgendaViewModel,
    path: String,
    syntax: SyntaxDocument,
    node: OrgNode,
    close: () -> Unit,
) {
    ModalPresence()
    val originalText = rememberSaveable(path, node.start, node.end) { syntax.text(node) }
    val original = remember(originalText) { PlanningDraft.parse(originalText) }
    if (original == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    var draft by
        rememberSaveable(path, node.start, node.end, stateSaver = PlanningDraftSaver) {
            mutableStateOf(original)
        }
    var note by rememberSaveable(path, node.start, node.end) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val dirty = draft != original || note.isNotEmpty()
    val valid =
        draft.timestamps.all { it.recurrence.isEmpty() || OrgRepeater.parse(it.recurrence) != null }
    fun dismiss() {
        if (!saving) {
            if (dirty) discard = true else close()
        }
    }
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { next ->
                if (next == SheetValue.Hidden && (saving || dirty)) {
                    if (!saving) discard = true
                    false
                } else true
            },
        )
    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("timestamp.sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(UiSpacing.edge)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.md),
        ) {
            Text(tr("timestamp"), style = MaterialTheme.typography.titleLarge)
            draft.keyword?.let { keyword ->
                Choice(
                    keyword,
                    listOf("SCHEDULED", "DEADLINE", "CLOSED"),
                    "planning_type",
                    { draft = draft.copy(keyword = it) },
                    presentation = ChoicePresentation.Segmented,
                ) {
                    context.label(it.lowercase())
                }
            }
            draft.timestamps.forEachIndexed { index, value ->
                PlanningField(
                    if (draft.timestamps.size == 1) "date" else if (index == 0) "start" else "end",
                    PlanningValue(true, value.date, value.time, value.recurrence),
                    allowDisable = false,
                ) { next ->
                    draft =
                        draft.copy(
                            timestamps =
                                draft.timestamps.mapIndexed { at, old ->
                                    if (at == index)
                                        old.copy(
                                            date = next.date,
                                            time = next.time,
                                            recurrence = next.repeater,
                                        )
                                    else old
                                }
                        )
                }
            }
            Field(
                note,
                { note = it },
                "note",
                Modifier.testTag("timestamp.note"),
                singleLine = false,
            )
            if (valid)
                Text(
                    draft.source(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("timestamp.source"),
                )
            else Text(tr("repeat_rule_invalid"), color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(shape = MaterialTheme.shapes.medium, onClick = ::dismiss, enabled = !saving) { Text(tr("cancel")) }
                Button(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val file = model.workspace.value.files.first { it.path == path }
                                    val source = model.repository.text(file)
                                    require(
                                        source == syntax.source.text &&
                                            SourceText(source).slice(node.start, node.end) ==
                                                original.original
                                    ) {
                                        "The source changed. Reopen this timestamp."
                                    }
                                    val doc =
                                        model.repository.engine.parse(
                                            path,
                                            source,
                                            model.workspace.value.configuration,
                                        )
                                    val next =
                                        model.repository.engine.editPlanningSource(
                                            doc,
                                            node,
                                            draft.source(),
                                            model.workspace.value.configuration,
                                            note,
                                        )
                                    model.repository.save(file.id, next, file.contentBlob)
                                }
                                com.roifewu.orgenda.platform.SyncWorker.enqueue(
                                    model.app,
                                    model.workspace.value.workspace?.id,
                                )
                                close()
                            } catch (e: Exception) {
                                error = e.message
                                saving = false
                            }
                        }
                    },
                    enabled = !saving && valid,
                ) {
                    Text(tr("save"))
                }
            }
        }
    }
    if (discard) DiscardDialog({ discard = false }, close)
}
