@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.roifewu.orgenda.ui

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.*
import android.text.method.LinkMovementMethod
import android.text.style.*
import android.widget.TextView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.roifewu.orgenda.*
import com.roifewu.orgenda.core.*
import io.ratex.RaTeXView
import io.ratex.RaTeXSpan
import kotlinx.coroutines.*
import java.nio.file.Paths

@Composable fun OrgPreview(model: OrgendaViewModel, editor: EditorState, openItem: (OrgHeading) -> Unit, complete: (OrgHeading) -> Unit) {
    var timestampEditorKey by rememberSaveable(editor.session) { mutableStateOf<String?>(null) }
    var stateProposal by remember { mutableStateOf<Pair<OrgHeading, String>?>(null) }
    val document = editor.document
    if (document == null) {
        androidx.compose.foundation.text.selection.SelectionContainer { Text(editor.text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) }
        return
    }
    var searchQuery by rememberSaveable(editor.session, editor.searchQuery) { mutableStateOf(editor.searchQuery) }
    LaunchedEffect(editor.session, editor.searchQuery) {
        if (searchQuery.isNotEmpty()) { delay(2_000); searchQuery = "" }
    }
    val syntax = remember(document) { document.syntax.groupParagraphs() }
    val keys = remember(document) { Outline.keys(document) }
    var headingDrop by remember { mutableStateOf<Pair<OrgHeading, OrgHeading>?>(null) }
    var collapsed by remember(editor.session) { mutableStateOf(model.collapsedHeadings[document.path].orEmpty()) }
    LaunchedEffect(document) { collapsed = model.collapsedHeadings[document.path].orEmpty() }
    fun toggle(heading: OrgHeading) {
        val key = keys.getValue(heading.start)
        collapsed = if (key in collapsed) collapsed - key else collapsed + key
        model.collapsedHeadings[document.path] = collapsed
    }
    val blocks = remember(document, collapsed) {
        val hidden = document.headings.filter { keys[it.start] in collapsed }
        syntax.blocks.filter { node -> node.type != "blank_line" && hidden.none { node.start >= it.end && node.start < it.subtreeEnd } }
    }
    val list = rememberLazyListState()
    var restoring by remember(editor.session) { mutableStateOf(false) }
    var appliedJumpByte by rememberSaveable(editor.session) { mutableStateOf<Int?>(null) }
    var appliedRevision by rememberSaveable(editor.session) { mutableStateOf<Long?>(null) }
    LaunchedEffect(editor.jumpByte, editor.navigationRevision, editor.session) {
        if (appliedJumpByte == editor.jumpByte && appliedRevision == editor.navigationRevision) return@LaunchedEffect
        restoring = true
        collapsed = collapsed - document.headings.filter { editor.jumpByte > it.start && editor.jumpByte < it.subtreeEnd }.map { keys.getValue(it.start) }.toSet()
        model.collapsedHeadings[document.path] = collapsed
        val hidden = document.headings.filter { keys[it.start] in collapsed }
        val revealed = syntax.blocks.filter { node -> node.type != "blank_line" && hidden.none { node.start >= it.end && node.start < it.subtreeEnd } }
        val index = revealed.indexOfLast { it.start <= editor.jumpByte }.coerceAtLeast(0)
        if (revealed.isNotEmpty()) {
            withFrameNanos { }
            list.scrollToItem(index)
            withFrameNanos { }
            val node = revealed[index]; val height = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
            val offset = ((editor.jumpByte - node.start).coerceAtLeast(0).toFloat() / (node.end - node.start).coerceAtLeast(1) * height).toInt()
            val context = if (searchQuery.isNotEmpty()) list.layoutInfo.viewportSize.height / 3 else 0
            list.scrollToItem(index, offset - context)
        }
        appliedJumpByte = editor.jumpByte; appliedRevision = editor.navigationRevision
        restoring = false
    }
    LaunchedEffect(list, blocks) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            if (restoring) return@collect
            blocks.getOrNull(index)?.let { node ->
                val height = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 1
                var byte = node.start + ((node.end - node.start - 1).coerceAtLeast(0) * (offset.toFloat() / height.coerceAtLeast(1)).coerceIn(0f, 1f)).toInt()
                while (!syntax.source.boundary(byte)) byte--
                model.reading(byte)
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("document.preview"), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(blocks, key = { "${it.type}:${it.start}" }) { node ->
            val source = syntax.text(node)
            when (node.type) {
                "heading" -> {
                    val item = document.headings.first { it.start == node.start }
                    Row(Modifier.fillMaxWidth().padding(start = ((item.level - 1).coerceAtMost(6) * 8).dp).semantics { heading() }.orgDrop("heading") { payload -> (payload as? OrgHeading)?.let { headingDrop = it to item } }, verticalAlignment = Alignment.CenterVertically) {
                        if (item.subtreeEnd > item.end) IconButton(onClick = { toggle(item) }) { Icon(if (keys[item.start] in collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore, tr(if (keys[item.start] in collapsed) "expand" else "collapse") + " " + item.title) } else Spacer(Modifier.width(48.dp))
                        if (item.canComplete) Checkbox(item.done, { complete(item) }, Modifier.semantics { contentDescription = item.title })
                        Column(Modifier.weight(1f).padding(vertical = 8.dp).combinedClickable(onClick = { openItem(item) }, onLongClick = { openItem(item) })) {
                            val titleNode = syntax.children(node).firstOrNull { it.type == "heading_title" }
                            if (titleNode != null) RichOrgText(syntax, titleNode, model, editor.file.path, textSize = if (item.level == 1) 20f else 18f, query = searchQuery, visibleStart = item.titleStart, visibleEnd = item.titleEnd) else Text(item.title)
                            if (item.priority.isNotEmpty()) Text(highlightedText("[#${item.priority}]", searchQuery), style = MaterialTheme.typography.labelSmall)
                            if (item.state.isNotEmpty() || item.tags.isNotEmpty()) Text(highlightedText((listOf(item.state) + item.tags.map { "#$it" }).filter { it.isNotEmpty() }.joinToString(" · "), searchQuery), color = workflowColor(item), style = MaterialTheme.typography.labelMedium)
                        }
                        HeadingMoveMenu(model, document, item, setState = if (item.isTask) { state ->
                            if (item.workflow.style(state).log.enter == "note" || item.workflow.style(item.state).log.leave == "note" || item.workflow.terminal(state) && model.workspace.value.configuration.logging.done == "note") stateProposal = item to state
                            else model.action { model.repository.mutate(item) { doc, heading -> model.repository.engine.transition(doc, heading, state, model.workspace.value.configuration) } }
                        } else null)
                    }
                    if (item.habit) HabitHistory(item)
                }
                "planning" -> {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        syntax.children(node).filter { it.type == "planning_entry" }.forEach { entry ->
                            TimestampControl(syntax.text(entry)) { timestampEditorKey = entry.timestampKey() }
                        }
                    }
                }
                "property_drawer", "drawer", "comment_block", "comment" -> {
                    var expanded by remember(node.start) { mutableStateOf(false) }
                    LaunchedEffect(searchQuery) { if (searchQuery.isNotEmpty() && SearchText.matches(source, searchQuery)) expanded = true }
                    Column {
                        TextButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null); Text(highlightedText(source.lineSequence().first().trim().trim(':'), searchQuery)) }
                        if (expanded) Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val content = syntax.children(node).filterNot { it.type.endsWith("_begin") || it.type.endsWith("_end") }
                            content.forEach { child ->
                                if (child.type == "clock") ClockPreview(syntax.text(child))
                                else RichOrgText(syntax, child, model, editor.file.path, query = searchQuery, readOnly = true)
                            }
                        }
                    }
                }
                "source_block", "example_block", "fixed_width", "export_block" -> {
                    val lines = source.lines()
                    val language = if (node.type == "source_block") lines.first().trim().split(Regex("\\s+")).getOrNull(1) ?: "" else ""
                    val body = if (node.type.endsWith("_block") && lines.size >= 2) lines.drop(1).dropLastWhile { it.isEmpty() || it.trimStart().startsWith("#+end", true) }.joinToString("\n") else source
                    CodeBlock(body, language, searchQuery)
                }
                "latex_block" -> Formula(source)
                "table" -> {
                    val rows = syntax.children(node)
                    fun cells(row: OrgNode): List<OrgNode?> {
                        val result = mutableListOf<OrgNode?>(); var cell: OrgNode? = null; var pipe = false
                        syntax.children(row).forEach { child -> if (child.type == "table_pipe") { if (pipe) { result += cell; cell = null }; pipe = true } else if (child.type == "table_cell") cell = child }
                        if (cell != null) result += cell
                        return result
                    }
                    val columns = rows.maxOfOrNull { cells(it).size } ?: 1
                    val widths = (0 until columns).map { column -> (rows.mapNotNull { cells(it).getOrNull(column)?.let(document.syntax::text)?.trim()?.length }.maxOrNull() ?: 1).coerceIn(8, 36) * 8 + 24 }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                            rows.forEach { row ->
                                if (row.type == "table_separator") HorizontalDivider(Modifier.width(widths.sum().dp))
                                else Row { for (column in 0 until columns) Box(Modifier.width(widths[column].dp).padding(8.dp)) {
                                    cells(row).getOrNull(column)?.let { RichOrgText(syntax, it, model, editor.file.path, query = searchQuery) } ?: Text(" ")
                                } }
                            }
                        }
                    }
                }
                "horizontal_rule" -> HorizontalDivider()
                "list" -> {
                    val items = syntax.children(node).filter { it.type == "list_item" }
                    Column {
                        items.forEach { item ->
                            val checkbox = syntax.children(item).firstOrNull { it.type == "checkbox" }
                            val checked = checkbox?.let { syntax.text(it).contains('X', true) } == true
                            val marker = syntax.children(item).firstOrNull { it.type.endsWith("list_marker") }
                            val indentation = marker?.let { syntax.text(it).takeWhile { char -> char == ' ' || char == '\t' }.length }?.coerceAtMost(12) ?: 0
                            Row(Modifier.padding(start = (indentation * 4).dp)) {
                                if (checkbox != null) Checkbox(syntax.text(checkbox).contains('X', true), onCheckedChange = { checked ->
                                    model.action {
                                        val next = model.repository.engine.toggleCheckbox(document, checkbox.start, checked)
                                        model.repository.save(editor.file.id, next, editor.baseline)
                                    }
                                }, modifier = Modifier.semantics { contentDescription = syntax.text(item).lineSequence().first().trim().replace(Regex("^[-+*] \\[[ Xx-]\\]\\s*"), "") })
                                Box(Modifier.weight(1f).padding(top = if (checkbox != null) 12.dp else 0.dp)) { RichOrgText(syntax, item, model, editor.file.path, omitCheckbox = checkbox != null, query = searchQuery, completed = checked) }
                            }
                        }
                    }
                }
                "keyword" -> if (source.startsWith("#+title:", true)) Text(highlightedText(source.substringAfter(':').trim(), searchQuery), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    else if (source.startsWith("#+caption:", true)) syntax.children(node).firstOrNull { it.type == "keyword_value" }?.let { RichOrgText(syntax, it, model, editor.file.path, textSize = 14f, query = searchQuery) }
                    else Text(highlightedText(source.trimEnd(), searchQuery), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                "clock" -> ClockPreview(source)
                "paragraph", "paragraph_group" -> {
                    val timestamp = syntax.descendants(node).firstOrNull { it.type in listOf("timestamp_range", "active_timestamp", "inactive_timestamp") && syntax.text(it).trim() == source.trim() }
                    if (timestamp != null && PlanningDraft.parse(syntax.text(timestamp)) != null) TimestampControl(syntax.text(timestamp)) { timestampEditorKey = timestamp.timestampKey() }
                    else RichOrgText(syntax, node, model, editor.file.path, query = searchQuery)
                }
                else -> {
                    if (node.type == "quote_block") Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                        Box(Modifier.padding(12.dp)) { RichOrgText(syntax, node, model, editor.file.path, query = searchQuery) }
                    } else RichOrgText(syntax, node, model, editor.file.path, query = searchQuery, readOnly = node.type == "dynamic_block", centered = node.type == "center_block")

                }
            }
        }
    }
    headingDrop?.let { (from, target) -> HeadingDropDialog(from, target, { headingDrop = null }) { placement -> model.moveHeading(from, target, placement); headingDrop = null } }
    timestampEditorKey?.let { requested -> syntax.nodes.firstOrNull { it.timestampKey() == requested }?.let { TimestampSheet(model, editor.file.path, syntax, it) { timestampEditorKey = null } } }
    stateProposal?.let { (item, state) -> ItemEditorSheet(model, item, state) { stateProposal = null } }
}

private fun latex(source: String): String {
    val value = source.trim()
    return when {
        value.startsWith("$$") && value.endsWith("$$") -> value.drop(2).dropLast(2)
        value.startsWith("\\[") && value.endsWith("\\]") || value.startsWith("\\(") && value.endsWith("\\)") -> value.drop(2).dropLast(2)
        value.startsWith('$') && value.endsWith('$') -> value.drop(1).dropLast(1)
        else -> value
    }
}
@Composable private fun Formula(source: String) {
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        AndroidView(factory = { RaTeXView(it) }, update = { it.latex = latex(source); it.fontSize = 20f * it.resources.configuration.fontScale; it.displayMode = true; it.color = color; it.contentDescription = latex(source) })
    }
}

@Composable private fun RichOrgText(syntax: SyntaxDocument, node: OrgNode, model: OrgendaViewModel, path: String, omitCheckbox: Boolean = false, textSize: Float = 16f, query: String = "", visibleStart: Int = node.start, visibleEnd: Int = node.end, splitImages: Boolean = true, readOnly: Boolean = false, completed: Boolean = false, centered: Boolean = false) {
    val currentEditor by model.editor.collectAsState()
    val document = currentEditor?.document?.takeIf { it.text == syntax.source.text }
    val images = remember(syntax, node, splitImages, visibleStart, visibleEnd) { if (splitImages) (listOf(node) + syntax.descendants(node)).distinct().mapNotNull { ImageReference.from(syntax, it) }.filter { it.start >= visibleStart && it.end <= visibleEnd } else emptyList() }
    if (images.isNotEmpty() && document != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var cursor = visibleStart
            images.forEach { image ->
                if (image.start > cursor && syntax.source.slice(cursor, image.start).isNotBlank()) RichOrgText(syntax, node, model, path, omitCheckbox, textSize, query, cursor, image.start, false, readOnly, completed, centered)
                PreviewImage(model, document, image); cursor = image.end
            }
            if (cursor < visibleEnd && syntax.source.slice(cursor, visibleEnd).isNotBlank()) RichOrgText(syntax, node, model, path, omitCheckbox, textSize, query, cursor, visibleEnd, false, readOnly, completed, centered)
        }
        return
    }
    val context = LocalContext.current
    var timestampEditorKey by rememberSaveable(path, node.start, node.end) { mutableStateOf<String?>(null) }
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val size = textSize * LocalConfiguration.current.fontScale
    val rich by produceState<SpannableStringBuilder?>(null, syntax, node.start, foreground, linkColor, size, query, visibleStart, visibleEnd, readOnly, completed) {
        value = withContext(Dispatchers.Default) {
            val output = SpannableStringBuilder()
            fun appendSource(start: Int, end: Int) { if (minOf(end, visibleEnd) > maxOf(start, visibleStart)) output.append(syntax.source.slice(maxOf(start, visibleStart), minOf(end, visibleEnd))) }
            suspend fun append(current: OrgNode) {
                if (current.end <= visibleStart || current.start >= visibleEnd) return
                if (current.start < visibleStart || current.end > visibleEnd) {
                    val children = syntax.children(current)
                    if (children.isEmpty()) appendSource(current.start, current.end) else {
                        var cursor = current.start
                        for (child in children) { appendSource(cursor, child.start); append(child); cursor = child.end }; appendSource(cursor, current.end)
                    }
                    return
                }
                val raw = syntax.text(current)
                val start = output.length
                val type = current.type
                if (type in listOf("timestamp_range", "active_timestamp", "inactive_timestamp") && PlanningDraft.parse(raw) != null) {
                    output.append(readableTimestamp(context, raw))
                    if (readOnly) return
                    output.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: android.view.View) { timestampEditorKey = current.timestampKey() }
                        override fun updateDrawState(ds: TextPaint) { ds.color = linkColor; ds.isUnderlineText = false }
                    }, start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    return
                }
                if (type.endsWith("_begin") || type.endsWith("_end")) return
                if (type == "checkbox" && omitCheckbox) return
                if (type in listOf("unordered_list_marker", "ordered_list_marker") && omitCheckbox) return
                if (type == "latex_fragment") {
                    val span = runCatching { RaTeXSpan.create(context, latex(raw), size, foreground) }.getOrNull()
                    if (span != null) { output.append(latex(raw)); output.setSpan(span, start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } else output.append(raw)
                    return
                }
                if (type in listOf("link", "plain_link", "angle_link")) {
                    val match = Regex("\\[\\[([^]]+)\\](?:\\[([^]]*)\\])?\\]").matchEntire(raw)
                    if (match != null || type != "link") {
                        val target = match?.groupValues?.get(1) ?: if (type == "angle_link") raw.drop(1).dropLast(1) else raw
                        output.append(match?.groupValues?.get(2)?.ifEmpty { target } ?: target)
                        output.setSpan(object : ClickableSpan() {
                            override fun onClick(widget: android.view.View) {
                                if (target.startsWith("https://") || target.startsWith("http://") || target.startsWith("mailto:")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                                else {
                                    model.openOrgLink(path, target)
                                }
                            }
                            override fun updateDrawState(ds: TextPaint) { ds.color = linkColor; ds.isUnderlineText = true }
                        }, start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        return
                    }
                }
                if (type in listOf("target", "radio_target", "statistics_cookie", "description_tag", "footnote_reference", "inline_footnote", "footnote_definition_marker")) {
                    val value = when (type) {
                        "target", "radio_target" -> "⌖ " + raw.trim('<', '>')
                        "statistics_cookie" -> raw.removeSurrounding("[", "]")
                        "description_tag" -> raw.trim().removeSuffix("::").trim()
                        else -> "[" + raw.trim().removePrefix("[fn:").removeSuffix("]").trim(':') + "]"
                    }
                    output.append(value); output.setSpan(ForegroundColorSpan(linkColor), start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (type in listOf("statistics_cookie", "description_tag")) output.setSpan(StyleSpan(Typeface.BOLD), start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    return
                }
                if (type in listOf("bold", "italic", "underline", "strike_through", "strikethrough", "code", "verbatim") && raw.length >= 2) {
                    val body = raw.substring(1, raw.length - 1)
                    output.append(body)
                    if (type !in listOf("code", "verbatim")) {
                        Regex("\\\\\\(.*?\\\\\\)|\\\\\\[.*?\\\\\\]|\\\$[^\\\$\\n]+\\\$").findAll(body).forEach { formula ->
                            runCatching { RaTeXSpan.create(context, latex(formula.value), size, foreground) }.getOrNull()?.let { output.setSpan(it, start + formula.range.first, start + formula.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                        }
                    }
                    val span: Any = when (type) { "bold" -> StyleSpan(Typeface.BOLD); "italic" -> StyleSpan(Typeface.ITALIC); "underline" -> UnderlineSpan(); "strike_through", "strikethrough" -> StrikethroughSpan(); else -> TypefaceSpan("monospace") }
                    output.setSpan(span, start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    val children = syntax.children(current)
                    if (children.isEmpty()) output.append(raw)
                    else {
                        var byte = current.start
                        for (child in children) {
                            if (child.start > byte) output.append(syntax.source.slice(byte, child.start))
                            append(child); byte = child.end
                        }
                        if (byte < current.end) output.append(syntax.source.slice(byte, current.end))
                    }
                }
            }
            append(node)
            while (output.isNotEmpty() && (output.last() == '\n' || output.last() == '\r')) output.delete(output.length - 1, output.length)
            if (completed && output.isNotEmpty()) output.setSpan(StrikethroughSpan(), 0, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            SearchText.ranges(output.toString(), query).forEach { match -> output.setSpan(BackgroundColorSpan(0x66ffca28), match.first, match.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            output
        }
    }
    AndroidView(factory = { TextView(it).apply { setTextIsSelectable(true); movementMethod = LinkMovementMethod.getInstance(); setPadding(0, 0, 0, 0) } }, modifier = Modifier.fillMaxWidth(), update = {
        it.setTextColor(foreground); it.setLinkTextColor(linkColor); it.textSize = textSize
        it.gravity = if (centered) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START
        it.text = rich ?: syntax.source.slice(visibleStart, visibleEnd).trimEnd()
    })
    timestampEditorKey?.let { requested -> syntax.nodes.firstOrNull { it.timestampKey() == requested }?.let { TimestampSheet(model, path, syntax, it) { timestampEditorKey = null } } }
}

@Composable private fun CodeBlock(source: String, language: String, query: String = "") {
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val secondary = MaterialTheme.colorScheme.secondary.toArgb()
    val tertiary = MaterialTheme.colorScheme.tertiary.toArgb()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val text by produceState<CharSequence>(source, source, language, accent, secondary, tertiary, muted, query) {
        value = withContext(Dispatchers.Default) {
            SpannableString(CodeHighlight.text(source, language, accent, secondary, tertiary, muted)).apply {
                SearchText.ranges(source, query).forEach { match -> setSpan(BackgroundColorSpan(0x66ffca28), match.first, match.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            if (language.isNotEmpty()) Text(language, style = MaterialTheme.typography.labelSmall)
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                AndroidView(factory = { TextView(it).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); textSize = 14f } }, update = { it.setTextColor(foreground); it.text = text })
            }
        }
    }
}


@Composable private fun highlightedText(source: String, query: String): androidx.compose.ui.text.AnnotatedString {
    val color = MaterialTheme.colorScheme.tertiaryContainer
    return androidx.compose.ui.text.buildAnnotatedString {
        append(source)
        SearchText.ranges(source, query).forEach { match -> addStyle(androidx.compose.ui.text.SpanStyle(background = color), match.first, match.last + 1) }
    }
}

private fun OrgNode.timestampKey() = "$type:$start:$end"
