@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roifewu.orgenda.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.OrgHeading
import com.roifewu.orgenda.platform.SyncWorker
import kotlinx.coroutines.*

@Composable
fun RefileSheet(
    model: OrgendaViewModel,
    item: OrgHeading,
    onMoved: (() -> Unit)? = null,
    close: () -> Unit,
) {
    ModalPresence()
    val snapshot = remember { model.workspace.value }
    val targets = remember {
        snapshot.documents.values
            .filter { it.path in snapshot.configuration.files.refile }
            .flatMap { it.headings }
            .filter {
                it.level <= snapshot.configuration.files.refileMaxLevel &&
                    (it.path != item.path || it.start !in item.start until item.subtreeEnd)
            }
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = { if (!saving) close() },
        sheetState =
            rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden || !saving },
            ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(tr("refile"), style = MaterialTheme.typography.headlineSmall)
            Text(item.title)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (targets.isEmpty()) Text(tr("no_refile_targets"), Modifier.padding(vertical = 24.dp))
            LazyColumn(Modifier.weight(1f, false)) {
                items(targets, key = { it.id }) { target ->
                    ListItem(
                        headlineContent = {
                            Text((target.ancestors + target.title).joinToString(" / "))
                        },
                        supportingContent = { Text(target.path) },
                        leadingContent = { if (target.isTask) WorkflowIcon(target) },
                        modifier =
                            Modifier.clickable(enabled = !saving) {
                                saving = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            model.saveNow()
                                            val current = model.workspace.value
                                            require(
                                                current.workspace?.id == snapshot.workspace?.id &&
                                                    current.configuration == snapshot.configuration
                                            ) {
                                                "Workspace or configuration changed"
                                            }
                                            require(
                                                current.documents[target.path]?.text ==
                                                    snapshot.documents.getValue(target.path).text
                                            ) {
                                                "Destination changed; reopen the move dialog"
                                            }
                                            model.repository.refile(item, target.path, target.start)
                                        }
                                        SyncWorker.enqueue(model.app, snapshot.workspace?.id)
                                        close()
                                        onMoved?.invoke()
                                    } catch (e: Exception) {
                                        error = e.message
                                        saving = false
                                    }
                                }
                            },
                    )
                }
            }
            TextButton(onClick = close, enabled = !saving) { Text(tr("cancel")) }
        }
    }
}
