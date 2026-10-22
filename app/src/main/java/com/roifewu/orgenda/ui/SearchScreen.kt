package com.roifewu.orgenda.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.*
import kotlinx.coroutines.*

data class SearchHit(val id: String, val path: String, val byte: Int, val title: String, val snippet: String, val kind: String, val item: OrgHeading? = null, val entry: JournalEntry? = null, val directory: Boolean = false)
private data class SearchRequest(val query: String, val scope: String, val revision: Long, val retry: Int)
private data class SearchResponse(val request: SearchRequest, val hits: List<SearchHit>, val error: String? = null)

internal fun searchTagSuggestions(items: List<OrgHeading>, scope: String): List<Pair<String, Int>> = items
    .filter { when (scope) { "tasks" -> it.kind in listOf("task", "project", "habit"); "calendar" -> it.agendaStamp != null; else -> true } }
    .flatMap { it.tags }.groupingBy { it }.eachCount().entries
    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    .take(8).map { it.key to it.value }

@Composable fun SearchScreen(model: OrgendaViewModel, snapshot: WorkspaceSnapshot, focusRequest: Int = 0, openFolder: (String) -> Unit = {}, settings: (String) -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf("all") }
    var handledFocusRequest by rememberSaveable { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    val preferences = remember { context.getSharedPreferences("search-history", android.content.Context.MODE_PRIVATE) }
    var history by remember { mutableStateOf(preferences.getString("recent", "")!!.split('\n').filter { it.isNotBlank() }) }
    val destinations = listOf("workspace", "appearance", "reminders", "workflow", "capture_templates", "files_agenda", "configuration")
    var journalDetail by remember(snapshot.workspace?.id) { mutableStateOf<JournalEntry?>(null) }
    var editing by key(snapshot.workspace?.id) { rememberHeadingSelection(model) }
    var completed by remember(snapshot.workspace?.id) { mutableStateOf<SearchResponse?>(null) }
    val request = SearchRequest(query.trim(), scope, snapshot.revision, retry)
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0 && focusRequest != handledFocusRequest) {
            handledFocusRequest = focusRequest; query = ""; focusRequester.requestFocus()
        }
    }
    LaunchedEffect(request, snapshot.workspace?.id) {
        if (request.query.isEmpty()) { completed = null; return@LaunchedEffect }
        delay(300)
        try {
            val results = withContext(Dispatchers.Default) {
                val matches = mutableListOf<SearchHit>()
                val query = request.query
                val scope = request.scope
                if (scope in listOf("all", "tasks", "calendar")) for (item in if (scope == "calendar") snapshot.items.filter { it.agendaStamp != null && !it.done }.sortedBy { it.agendaStamp?.date } else snapshot.allItems) {
                    ensureActive()
                    if (scope == "tasks" && item.kind !in listOf("task", "project", "habit")) continue
                    val tags = item.tags.filter { SearchText.matches(it, query) }
                    if (SearchText.matches(item.title, query) || SearchText.matches(item.body, query) || tags.isNotEmpty()) matches += SearchHit("item:${item.id}", item.path, item.start, item.title, if (tags.isEmpty()) SearchText.excerpt(item.body, query) else tags.joinToString("  ") { "#$it" }, "tasks", item)
                }
                if (scope in listOf("all", "journal")) snapshot.journals.forEach { entry ->
                    ensureActive()
                    if (SearchText.matches(entry.title, query) || SearchText.matches(entry.body, query)) matches += SearchHit("journal:${entry.id}", entry.path, entry.start, entry.title, SearchText.excerpt(entry.body, query), "journal", entry = entry)
                }
                if (scope in listOf("all", "files")) for (file in snapshot.files.filter { !it.trashed && it.path != "config.json" && !it.path.split('/').any { part -> part.startsWith('.') } }) {
                    ensureActive()
                    val source = if (file.directory) "" else withContext(Dispatchers.IO) { model.repository.text(file) }
                    val range = SearchText.range(source, query)
                    if (range != null || SearchText.matches(file.path.substringAfterLast('/'), query)) matches += SearchHit("file:${file.id}", file.path, range?.let { SourceText(source).byteOffset(it.first) } ?: 0, file.path.substringAfterLast('/'), SearchText.excerpt(source, query), "files", directory = file.directory)
                }
                if (scope in listOf("all", "settings")) destinations.forEach { key ->
                    val terms = context.label(key) + " " + context.label("search_terms_$key") + " " + key.replace('_', ' ') + if (key == "configuration") " Emacs prompt config.json Extract from Emacs Copy prompt Full conversion prompt 从 Emacs 提取 從 Emacs 擷取 复制提示词 複製提示詞 查看完整转换提示词 檢視完整轉換提示詞 转换、检查并导入设置 轉換、檢查並匯入設定" else ""
                    if (SearchText.matches(terms, query)) matches += SearchHit("setting:$key", key, 0, context.label(key), context.label("search_terms_$key"), "settings")
                }
                matches
            }
            completed = SearchResponse(request, results)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { completed = SearchResponse(request, emptyList(), e.message ?: context.label("operation_failed")) }
    }
    fun rememberQuery() {
        val clean = query.trim().replace('\n', ' '); if (clean.isEmpty()) return
        history = (listOf(clean) + history.filter { it != clean }).take(12); preferences.edit().putString("recent", history.joinToString("\n")).apply()
    }
    val response = completed
    val loading = response?.request != request
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp).focusRequester(focusRequester).testTag("search.query"), label = { Text(tr("search_hint")) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { rememberQuery(); focusManager.clearFocus() }),
            leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, tr("clear")) } })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("all", "tasks", "journal", "calendar", "files", "settings").forEach { key -> FilterChip(scope == key, { scope = key }, label = { Text(tr(key)) }) } }
        if (request.query.isEmpty()) {
            val tags = if (scope in listOf("all", "tasks", "calendar")) searchTagSuggestions(snapshot.allItems, scope) else emptyList()
            val journals = if (scope in listOf("all", "journal")) snapshot.journals.sortedByDescending { it.date }.take(3) else emptyList()
            val files = if (scope in listOf("all", "files")) snapshot.files.filter { !it.directory && !it.trashed && it.path != "config.json" && !it.path.split('/').any { part -> part.startsWith('.') } }.take(4) else emptyList()
            if (history.isEmpty() && tags.isEmpty() && journals.isEmpty() && files.isEmpty() && scope != "settings") {
                SearchEmptyContent(scope, guidance = true, onAll = { scope = "all" }, onClear = { query = "" })
            } else LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (history.isNotEmpty()) {
                    item { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text(tr("recent_searches"), Modifier.weight(1f)); TextButton(onClick = { history = emptyList(); preferences.edit().remove("recent").apply() }) { Text(tr("clear")) } } }
                    items(history, key = { "history:$it" }) { value -> ListItem(headlineContent = { Text(value) }, leadingContent = { Icon(Icons.Default.History, null) }, modifier = Modifier.clickable { query = value }) }
                }
                if (tags.isNotEmpty()) {
                    item { SearchSectionTitle("try_tag") }
                    item { Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { tags.forEach { (tag, _) -> SuggestionChip(onClick = { query = tag }, label = { Text("#$tag") }) } } }
                }
                if (journals.isNotEmpty()) {
                    item { SearchSectionTitle("recent_journals") }
                    items(journals, key = { "journal:${it.id}" }) { entry -> SearchResultItem(SearchHit("journal:${entry.id}", entry.path, entry.start, entry.title, SearchText.excerpt(entry.body, ""), "journal", entry = entry), "") { journalDetail = entry; focusManager.clearFocus() } }
                }
                if (files.isNotEmpty()) {
                    item { SearchSectionTitle("browse_files") }
                    items(files, key = { "file:${it.id}" }) { file -> ListItem(headlineContent = { Text(file.path.substringAfterLast('/')) }, supportingContent = { Text(file.path) }, leadingContent = { Icon(Icons.Default.Description, null) }, modifier = Modifier.clickable { focusManager.clearFocus(); model.open(file) }) }
                }
                if (scope == "settings") items(destinations) { key -> ListItem(headlineContent = { Text(tr(key)) }, supportingContent = { Text(tr("search_terms_$key")) }, leadingContent = { Icon(Icons.Default.Settings, null) }, modifier = Modifier.clickable { focusManager.clearFocus(); settings(key) }) }
            }
        } else if (response?.hits?.isNotEmpty() == true) LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(if (response.request.scope == "all") "all_results" else response.request.scope), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (loading) CircularProgressIndicator(Modifier.size(20.dp).testTag("search.loading"), strokeWidth = 2.dp)
                Text(response.hits.size.toString() + " " + tr("results_found"))
            } }
            items(response.hits, key = { it.id }) { hit ->
                SearchResultItem(hit, response.request.query) {
                    rememberQuery(); focusManager.clearFocus()
                    if (hit.kind == "settings") settings(hit.path) else if (hit.item != null) editing = hit.item else if (hit.entry != null) journalDetail = hit.entry
                    else if (hit.directory) openFolder(hit.path) else snapshot.files.firstOrNull { it.path == hit.path }?.let { model.open(it, hit.byte, response.request.query) }
                }
                HorizontalDivider()
            }
        } else if (loading) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.testTag("search.loading")); Text(tr("searching")) }
        } else if (response?.error != null) Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(response.error, color = MaterialTheme.colorScheme.error); Button(onClick = { retry++ }) { Text(tr("retry")) }
        } else SearchEmptyContent(scope, guidance = false, onAll = { scope = "all" }, onClear = { query = "" })
    }
    journalDetail?.let { entry -> JournalDetailSheet(entry, { journalDetail = null }) { journalDetail = null; snapshot.files.firstOrNull { it.path == entry.path }?.let { model.open(it, entry.start, query.trim()) } } }
    editing?.let { ItemEditorSheet(model, it) { editing = null } }
}

@Composable private fun SearchSectionTitle(key: String) { Text(tr(key), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall) }

@Composable internal fun SearchEmptyContent(scope: String, guidance: Boolean, onAll: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Default.Search, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(tr(if (guidance) "start_search" else "no_results"), style = MaterialTheme.typography.titleMedium)
        Text(tr(if (guidance) "search_guidance" else if (scope == "all") "search_no_matches_hint" else "search_scope_hint"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (scope != "all") Button(onClick = onAll, modifier = Modifier.testTag("search.expandScope")) { Text(tr("search_all")) }
        if (!guidance) OutlinedButton(onClick = onClear, modifier = Modifier.testTag("search.clear")) { Text(tr("clear_search")) }
    }
}

@Composable internal fun SearchResultItem(hit: SearchHit, query: String, open: () -> Unit) {
    ListItem(headlineContent = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        hit.item?.takeIf { it.state.isNotBlank() }?.let { WorkflowIcon(it) }
        HighlightText(hit.title, query, maxLines = Int.MAX_VALUE, color = hit.item?.let { workflowColor(it) } ?: LocalContentColor.current)
    } }, supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hit.snippet.isNotEmpty()) HighlightText(hit.snippet, query)
        Text(when {
            hit.kind == "settings" -> ""
            hit.entry != null -> hit.entry.date.format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM))
            hit.item != null -> listOf(tr(if (hit.item.kind == "note") "note_kind" else hit.item.kind), hit.item.state, hit.path).filter { it.isNotBlank() }.joinToString(" · ")
            else -> hit.path
        }, style = MaterialTheme.typography.bodySmall)
    } }, leadingContent = { Icon(when { hit.directory -> Icons.Default.Folder; hit.kind == "files" -> Icons.Default.Description; hit.kind == "settings" -> Icons.Default.Settings; hit.kind == "journal" -> Icons.Default.Book; hit.item?.kind == "event" -> Icons.Default.Event; else -> Icons.Default.Checklist }, null) }, modifier = Modifier.clickable(onClick = open))
}

@Composable fun HighlightText(text: String, query: String, maxLines: Int = 3, color: Color = LocalContentColor.current) {
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val foreground = MaterialTheme.colorScheme.onTertiaryContainer
    val value = remember(text, query, highlight, foreground) { buildAnnotatedString {
        append(text); SearchText.ranges(text, query).forEach { addStyle(SpanStyle(background = highlight, color = foreground), it.first, it.last + 1) }
    } }
    Text(value, maxLines = maxLines, overflow = TextOverflow.Ellipsis, color = color)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable private fun JournalDetailSheet(entry: JournalEntry, close: () -> Unit, showFile: () -> Unit) {
    ModalPresence()
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(entry.date.format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)), style = MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.text.selection.SelectionContainer { Column { Text(entry.title, style = MaterialTheme.typography.headlineSmall); Text(entry.body) } }
            Row { TextButton(onClick = showFile) { Text(tr("show_in_file")) }; TextButton(onClick = close) { Text(tr("close")) } }
        }
    }
}
