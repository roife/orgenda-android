@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.roifewu.orgenda.ui

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.*
import android.text.method.LinkMovementMethod
import android.text.style.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.*
import com.roifewu.orgenda.core.*
import io.ratex.RaTeXEngine
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer
import io.ratex.RaTeXSpan
import kotlinx.coroutines.*
import kotlin.math.roundToInt

private val previewDisclosureWidth = 24.dp
private val previewStateWidth = 32.dp
private val previewControlHeight = 44.dp
private val previewCheckboxHeight = 32.dp

private fun OrgHeading.previewContentInset() =
    previewDisclosureWidth + if (canComplete) previewStateWidth else 0.dp

@Composable
fun OrgPreview(
    model: OrgendaViewModel,
    editor: EditorState,
    openItem: (OrgHeading) -> Unit,
    complete: (OrgHeading) -> Unit,
    bottomPadding: Dp = UiSpacing.sectionInset,
) {
    var timestampEditorKey by rememberSaveable(editor.session) { mutableStateOf<String?>(null) }
    var stateProposal by remember { mutableStateOf<Pair<OrgHeading, String>?>(null) }
    val document = editor.document
    if (document == null) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                editor.text,
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = UiSpacing.edge,
                        end = UiSpacing.edge,
                        top = UiSpacing.edge,
                        bottom = bottomPadding,
                    ),
                style = DocumentTypography.body,
            )
        }
        return
    }
    var searchQuery by
        rememberSaveable(editor.session, editor.searchQuery) { mutableStateOf(editor.searchQuery) }
    LaunchedEffect(editor.session, editor.searchQuery) {
        if (searchQuery.isNotEmpty()) {
            delay(2_000)
            searchQuery = ""
        }
    }
    val syntax = remember(document) { document.syntax.groupParagraphs() }
    val rows = remember(syntax) { previewBlocks(syntax) }
    val keys = remember(document) { Outline.keys(document) }
    var collapsed by
        remember(editor.session) {
            mutableStateOf(model.collapsedHeadings[document.path].orEmpty())
        }
    LaunchedEffect(document) { collapsed = model.collapsedHeadings[document.path].orEmpty() }
    fun toggle(heading: OrgHeading) {
        val key = keys.getValue(heading.start)
        collapsed = if (key in collapsed) collapsed - key else collapsed + key
        model.collapsedHeadings[document.path] = collapsed
    }
    val blocks =
        remember(document, collapsed) {
            val hidden = document.headings.filter { keys[it.start] in collapsed }
            rows.filter { node ->
                hidden.none { node.start >= it.end && node.start < it.subtreeEnd }
            }
        }
    val list = rememberLazyListState()
    val headingDrag = remember(editor.session) { OutlineDragState() }
    val visibleHeadings = remember(document, blocks) {
        val starts = blocks.filter { it.type == "heading" }.mapTo(mutableSetOf()) { it.start }
        document.headings.filter { it.start in starts }
    }
    val headingDrop by remember(visibleHeadings, editor.session) {
        derivedStateOf {
            outlineDropAt(headingDrag, list, visibleHeadings, editor.session) { "heading:${it.start}" }
        }
    }
    var restoring by remember(editor.session) { mutableStateOf(false) }
    var appliedJumpByte by rememberSaveable(editor.session) { mutableStateOf<Int?>(null) }
    var appliedRevision by rememberSaveable(editor.session) { mutableStateOf<Long?>(null) }
    LaunchedEffect(editor.jumpByte, editor.navigationRevision, editor.session) {
        if (appliedJumpByte == editor.jumpByte && appliedRevision == editor.navigationRevision)
            return@LaunchedEffect
        restoring = true
        collapsed =
            collapsed -
                document.headings
                    .filter { editor.jumpByte > it.start && editor.jumpByte < it.subtreeEnd }
                    .map { keys.getValue(it.start) }
                    .toSet()
        model.collapsedHeadings[document.path] = collapsed
        val hidden = document.headings.filter { keys[it.start] in collapsed }
        val revealed = rows.filter { node ->
            hidden.none { node.start >= it.end && node.start < it.subtreeEnd }
        }
        val target =
            revealed.firstOrNull { it.start <= editor.jumpByte && editor.jumpByte < it.end }
                ?: revealed.filter { it.start <= editor.jumpByte }.maxByOrNull { it.start }
        val index = revealed.indexOf(target).coerceAtLeast(0)
        if (revealed.isNotEmpty()) {
            withFrameNanos {}
            list.scrollToItem(index)
            withFrameNanos {}
            val node = revealed[index]
            val height =
                list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
            val offset =
                ((editor.jumpByte - node.start).coerceAtLeast(0).toFloat() /
                        (node.end - node.start).coerceAtLeast(1) * height)
                    .toInt()
            val context =
                if (searchQuery.isNotEmpty()) list.layoutInfo.viewportSize.height / 3 else 0
            list.scrollToItem(index, offset - context)
        }
        appliedJumpByte = editor.jumpByte
        appliedRevision = editor.navigationRevision
        restoring = false
    }
    LaunchedEffect(list, blocks) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (restoring) return@collect
                blocks.getOrNull(index)?.let { node ->
                    val height =
                        list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size
                            ?: 1
                    var byte =
                        node.start +
                            ((node.end - node.start - 1).coerceAtLeast(0) *
                                    (offset.toFloat() / height.coerceAtLeast(1)).coerceIn(0f, 1f))
                                .toInt()
                    while (!syntax.source.boundary(byte)) byte--
                    model.reading(byte)
                }
            }
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides previewControlHeight) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 900.dp).fillMaxSize().testTag("document.preview")
                    .previewHeadingDrop(headingDrag, list, visibleHeadings, editor.session) { from, target, placement ->
                        model.moveHeading(from, target, placement)
                    },
                state = list,
                contentPadding =
                    PaddingValues(
                        start = UiSpacing.edge,
                        end = UiSpacing.edge,
                        top = UiSpacing.controlGap,
                        bottom = bottomPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.xxs),
            ) {
                items(blocks, key = { "${it.type}:${it.start}" }) { node ->
                    val parent =
                        document.headings.lastOrNull {
                            it.start < node.start && it.subtreeEnd > node.start
                        }
                    val heading =
                        if (node.type == "heading")
                            document.headings.firstOrNull { it.start == node.start }
                        else null
                    val level = if (node.type == "heading") heading?.level ?: 1 else parent?.level
                    val indentation =
                        UiSpacing.outlineIndent * ((level ?: 1) - 1).coerceIn(0, 8) +
                            if (node.type == "heading") 0.dp
                            else parent?.previewContentInset() ?: 0.dp
                    val spacing =
                        when (node.type) {
                            "heading" -> 10
                            "paragraph_group" -> 8
                            "list_item" -> 0
                            else -> 2
                        }
                    Box(
                        Modifier.fillMaxWidth()
                            .then(if (heading != null) Modifier.headingDropIndicator(
                                heading, headingDrop?.takeIf { it.target.start == heading.start }?.placement,
                            ) else Modifier)
                            .padding(
                                start = indentation,
                                top = if (node == blocks.firstOrNull()) 0.dp else spacing.dp,
                            )
                            .testTag("preview.block.${node.start}")
                    ) {
                        PreviewBlock(
                            model,
                            editor,
                            syntax,
                            node,
                            searchQuery,
                            collapsed,
                            keys,
                            openItem,
                            complete,
                            ::toggle,
                            { from, target, placement ->
                                model.moveHeading(from, target, placement)
                            },
                            { timestampEditorKey = it.timestampKey() },
                            { item, state ->
                                if (state != item.state) {
                                    if (
                                        item.workflow.style(state).log.enter == "note" ||
                                            item.workflow.style(item.state).log.leave == "note" ||
                                            item.workflow.terminal(state) &&
                                                model.workspace.value.configuration.logging.done ==
                                                    "note"
                                    )
                                        stateProposal = item to state
                                    else
                                        model.action {
                                            model.repository.mutate(item) { doc, heading ->
                                                model.repository.engine.transition(
                                                    doc,
                                                    heading,
                                                    state,
                                                    model.workspace.value.configuration,
                                                )
                                            }
                                        }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    timestampEditorKey?.let { requested ->
        syntax.nodes
            .firstOrNull { it.timestampKey() == requested }
            ?.let {
                TimestampSheet(model, editor.file.path, syntax, it) { timestampEditorKey = null }
            }
    }
    stateProposal?.let { (item, state) ->
        ItemEditorSheet(model, item, state) { stateProposal = null }
    }
}

private fun previewBlocks(syntax: SyntaxDocument): List<OrgNode> {
    val ordered = mutableListOf<OrgNode>()
    val nodes = syntax.blocks
    var index = 0
    while (index < nodes.size) {
        val node = nodes[index++]
        ordered += node
        if (node.type != "heading") continue
        val metadata = mutableListOf<OrgNode>()
        while (
            index < nodes.size &&
                nodes[index].type in listOf("planning", "property_drawer", "drawer", "blank_line")
        ) metadata += nodes[index++]
        ordered += metadata.filter { it.type in listOf("property_drawer", "drawer") }
        ordered += metadata.filter { it.type == "planning" }
    }
    return ordered
        .filterNot { it.type == "blank_line" }
        .flatMap {
            if (it.type == "list") syntax.children(it).filter { child -> child.type == "list_item" }
            else listOf(it)
        }
}

@Composable
private fun PreviewBlock(
    model: OrgendaViewModel,
    editor: EditorState,
    syntax: SyntaxDocument,
    node: OrgNode,
    searchQuery: String,
    collapsed: Set<String>,
    keys: Map<Int, String>,
    openItem: (OrgHeading) -> Unit,
    complete: (OrgHeading) -> Unit,
    onToggle: (OrgHeading) -> Unit,
    onDrop: (OrgHeading, OrgHeading, HeadingPlacement) -> Unit,
    onTimestamp: (OrgNode) -> Unit,
    onState: (OrgHeading, String) -> Unit,
    readOnly: Boolean = false,
) {
    val document = editor.document ?: return
    val bodySize = if (readOnly) DocumentTypography.Subheadline else DocumentTypography.Body
    val source = syntax.text(node)
    when (node.type) {
        "heading" -> {
            val item = document.headings.firstOrNull { it.start == node.start }
            if (item == null || readOnly) {
                RichOrgText(
                    syntax,
                    node,
                    model,
                    editor.file.path,
                    textSize = bodySize,
                    query = searchQuery,
                    readOnly = readOnly,
                )
                return
            }
            val controlHeight = UiSizes.icon
            val titleSize = DocumentTypography.headingSize(item.level, item.isTask)
            val titleWeight = DocumentTypography.headingWeight(item.isTask).weight
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val baselineFromCenter = remember(context, configuration, titleSize, titleWeight) {
                val metrics = Paint().apply {
                    textSize = DocumentTypography.scaledDp(context, titleSize) *
                        context.resources.displayMetrics.density
                    typeface = Typeface.create(Typeface.DEFAULT, titleWeight, false)
                }.fontMetrics
                (-(metrics.ascent + metrics.descent) / 2).roundToInt()
            }
            // Share the native title's first baseline instead of estimating a top inset.
            // Compact visual controls retain Compose's expanded minimum touch target.
            Column(Modifier.fillMaxWidth()) {
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentSize provides controlHeight
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .semantics { heading() }
                            .orgDrag("heading", HeadingDrag(item, editor.session))
                            .semantics {
                                customActions = headingMoveTargets(document, item).mapNotNull { (label, target, position) ->
                                    target?.let {
                                        CustomAccessibilityAction(model.app.label(label)) {
                                            onDrop(item, it, position)
                                            true
                                        }
                                    }
                                } + if (item.isTask) item.workflow.tokens.map { state ->
                                    CustomAccessibilityAction(item.workflow.style(state).label.ifEmpty { state }) {
                                        onState(item, state)
                                        true
                                    }
                                } else emptyList()
                            },
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (
                            syntax.blocks.any {
                                it.type != "blank_line" &&
                                    it.start >= item.end &&
                                    it.start < item.subtreeEnd
                            }
                        )
                            IconButton(
                                onClick = { onToggle(item) },
                                modifier =
                                    Modifier.alignBy { it.measuredHeight / 2 + baselineFromCenter }
                                        .width(previewDisclosureWidth).height(controlHeight),
                            ) {
                                Icon(
                                    if (keys[item.start] in collapsed) Icons.Default.ChevronRight
                                    else Icons.Default.ExpandMore,
                                    tr(
                                        if (keys[item.start] in collapsed) "expand" else "collapse"
                                    ) + " " + item.title,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        else Spacer(Modifier.width(previewDisclosureWidth))
                        val stateDescriptionText = tr("state") + " " + item.title
                        if (item.isTask)
                            IconButton(
                                onClick = { complete(item) },
                                enabled = item.canComplete,
                                modifier =
                                    Modifier.alignBy { it.measuredHeight / 2 + baselineFromCenter }
                                        .width(previewStateWidth)
                                        .height(controlHeight)
                                        .semantics {
                                            contentDescription = stateDescriptionText
                                            stateDescription =
                                                item.workflow.style(item.state).label.ifEmpty {
                                                    item.state
                                                }
                                        },
                            ) {
                                WorkflowIcon(item)
                            }
                        else if (item.canComplete)
                            Checkbox(
                                item.done,
                                { complete(item) },
                                Modifier.alignBy { it.measuredHeight / 2 + baselineFromCenter }
                                    .width(previewStateWidth).height(controlHeight).semantics {
                                    contentDescription = item.title
                                },
                            )
                        Column(
                            Modifier.weight(1f)
                                .alignBy(FirstBaseline)
                                .testTag("preview.heading.title.${item.start}")
                                .clickable { openItem(item) }
                        ) {
                            val titleNode =
                                syntax.children(node).firstOrNull { it.type == "heading_title" }
                            val titleHasImage =
                                titleNode?.let { title ->
                                    syntax.descendants(title).any {
                                        ImageReference.from(syntax, it) != null
                                    }
                                } == true
                            if (titleNode != null)
                                RichOrgText(
                                    syntax,
                                    titleNode,
                                    model,
                                    editor.file.path,
                                    textSize = titleSize,
                                    query = searchQuery,
                                    foregroundOverride =
                                        if (item.isTask) workflowColor(item).toArgb() else null,
                                    fontWeight = titleWeight,
                                    headingDrag = HeadingDrag(item, editor.session),
                                    visibleStart = item.titleStart,
                                    visibleEnd = item.titleEnd,
                                    activate =
                                        if (titleHasImage) null
                                        else {
                                            { openItem(item) }
                                        },
                                )
                            else Text(item.title, style = DocumentTypography.heading(item.level, item.isTask))
                            if (item.priority.isNotEmpty() || item.tags.isNotEmpty())
                                FlowRow(
                                    Modifier.padding(top = UiSpacing.xxs)
                                        .clickable { openItem(item) },
                                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm),
                                    verticalArrangement = Arrangement.spacedBy(UiSpacing.xxs),
                                ) {
                                    if (item.priority.isNotEmpty())
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                            shape = MaterialTheme.shapes.extraSmall,
                                        ) {
                                            Text(
                                                highlightedText(item.priority, searchQuery),
                                                Modifier.padding(horizontal = UiSpacing.priorityLabelHorizontal, vertical = UiSpacing.xxs),
                                                style =
                                                    DocumentTypography.caption.copy(
                                                        fontWeight =
                                                            androidx.compose.ui.text.font.FontWeight.Medium
                                                    ),
                                            )
                                        }
                                    if (item.tags.isNotEmpty())
                                        Text(
                                            highlightedText(
                                                item.tags.joinToString("  ") { "#$it" },
                                                searchQuery,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = DocumentTypography.metadata,
                                        )
                                }
                        }

                    }
                }
                if (item.habit)
                    Box(Modifier.fillMaxWidth().padding(start = item.previewContentInset())) {
                        HabitHistory(item)
                    }
            }
        }
        "planning" -> {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiSpacing.controlGap)) {
                syntax
                    .children(node)
                    .filter { it.type == "planning_entry" }
                    .forEach { entry ->
                        if (readOnly)
                            Text(
                                readableTimestamp(LocalContext.current, syntax.text(entry)),
                                style = DocumentTypography.subheadline,
                            )
                        else TimestampControl(syntax.text(entry)) { onTimestamp(entry) }
                    }
            }
        }
        "property_drawer",
        "drawer",
        "comment_block",
        "comment" -> {
            var expanded by
                rememberSaveable(editor.session, node.start, node.type) { mutableStateOf(false) }
            val comment = node.type in listOf("comment", "comment_block")
            LaunchedEffect(searchQuery) {
                if (searchQuery.isNotEmpty() && SearchText.matches(source, searchQuery))
                    expanded = true
            }
            val drawerName = source.lineSequence().first().trim().trim(':')
            val drawerState = tr(if (expanded) "collapse" else "expand")
            Surface(
                color =
                    if (comment) MaterialTheme.colorScheme.surface
                    else uiColors().drawerContainer,
                shape = MaterialTheme.shapes.small,
                modifier =
                    if (comment) Modifier
                    else Modifier.fillMaxWidth().testTag("document.drawer.${node.start}"),
            ) {
                Column(Modifier.padding(horizontal = UiSpacing.controlGap)) {
                    if (comment)
                        TextButton(shape = MaterialTheme.shapes.medium, onClick = { expanded = !expanded }) {
                            Icon(
                                if (expanded) Icons.Default.ExpandMore
                                else Icons.Default.ChevronRight,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                highlightedText(tr("comment"), searchQuery),
                                style = DocumentTypography.subheadline,
                            )
                        }
                    else
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 24.dp)
                                .clickable(onClickLabel = drawerState, role = Role.Button) {
                                    expanded = !expanded
                                }
                                .padding(vertical = UiSpacing.xxs),
                            horizontalArrangement = Arrangement.spacedBy(UiSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (expanded) Icons.Default.ExpandMore
                                else Icons.Default.ChevronRight,
                                null,
                                Modifier.size(UiSizes.iconSmall),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                highlightedText(drawerName, searchQuery),
                                style = DocumentTypography.caption2,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    if (expanded)
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(
                                    start = if (comment) 12.dp else 0.dp,
                                    top = 2.dp,
                                    bottom = 6.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(UiSpacing.xs),
                        ) {
                            if (comment) {
                                val content =
                                    if (node.type == "comment_block")
                                        source
                                            .lines()
                                            .drop(1)
                                            .dropLastWhile {
                                                it.isEmpty() ||
                                                    it.trimStart().startsWith("#+end", true)
                                            }
                                            .joinToString("\n")
                                    else
                                        syntax
                                            .children(node)
                                            .firstOrNull { it.type == "comment_text" }
                                            ?.let(syntax::text) ?: source.removePrefix("# ")
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(
                                        highlightedText(content.trimEnd(), searchQuery),
                                        style =
                                            DocumentTypography.subheadline.copy(
                                                fontStyle =
                                                    androidx.compose.ui.text.font.FontStyle.Italic
                                            ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else
                                syntax
                                    .children(node)
                                    .filterNot {
                                        it.type.endsWith("_begin") ||
                                            it.type.endsWith("_end") ||
                                            it.type == "blank_line"
                                    }
                                    .forEachIndexed { index, child ->
                                        Box(
                                            Modifier.padding(
                                                top =
                                                    if (
                                                        index > 0 && child.type == "paragraph_group"
                                                    )
                                                        UiSpacing.sm
                                                    else 0.dp
                                            )
                                        ) {
                                            PreviewBlock(
                                                model,
                                                editor,
                                                syntax,
                                                child,
                                                searchQuery,
                                                collapsed,
                                                keys,
                                                openItem,
                                                complete,
                                                onToggle,
                                                onDrop,
                                                onTimestamp,
                                                onState,
                                                readOnly = true,
                                            )
                                        }
                                    }
                        }
                }
            }
        }
        "property" ->
            Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.xxs)) {
                syntax
                    .children(node)
                    .firstOrNull { it.type == "property_name" }
                    ?.let {
                        Text(
                            highlightedText(syntax.text(it).trim(), searchQuery),
                            style =
                                DocumentTypography.caption.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                syntax
                    .children(node)
                    .firstOrNull { it.type == "property_value" }
                    ?.let {
                        RichOrgText(
                            syntax,
                            it,
                            model,
                            editor.file.path,
                            textSize = DocumentTypography.Subheadline,
                            query = searchQuery,
                            readOnly = true,
                            trimSpaces = true,
                        )
                    }
            }
        "source_block",
        "example_block",
        "fixed_width",
        "export_block" -> {
            val lines = source.lines()
            val language =
                if (node.type == "source_block")
                    lines.first().trim().split(Regex("\\s+")).getOrNull(1) ?: ""
                else ""
            val body =
                if (node.type == "fixed_width")
                    syntax.children(node).joinToString("\n") { line ->
                        syntax
                            .children(line)
                            .firstOrNull { it.type == "fixed_width_content" }
                            ?.let(syntax::text)
                            .orEmpty()
                    }
                else if (lines.size >= 2)
                    lines
                        .drop(1)
                        .dropLastWhile { it.isEmpty() || it.trimStart().startsWith("#+end", true) }
                        .joinToString("\n")
                else source
            val label =
                when (node.type) {
                    "example_block" -> tr("block_example")
                    "export_block" ->
                        listOf(
                                lines
                                    .first()
                                    .trim()
                                    .split(Regex("\\s+"))
                                    .getOrNull(1)
                                    .orEmpty()
                                    .uppercase(),
                                tr("block_export"),
                            )
                            .filter { it.isNotEmpty() }
                            .joinToString(" ")
                    "fixed_width" -> tr("block_fixed_width")
                    else -> language.ifEmpty { tr("block_code") }
                }
            CodeBlock(body, language, searchQuery, label)
        }
        "latex_block" -> Formula(source)
        "table" -> {
            val rows = syntax.children(node)
            fun cells(row: OrgNode): List<OrgNode?> {
                val result = mutableListOf<OrgNode?>()
                var cell: OrgNode? = null
                var pipe = false
                syntax.children(row).forEach { child ->
                    if (child.type == "table_pipe") {
                        if (pipe) {
                            result += cell
                            cell = null
                        }
                        pipe = true
                    } else if (child.type == "table_cell") cell = child
                }
                if (cell != null) result += cell
                return result
            }
            val columns = rows.maxOfOrNull { cells(it).size } ?: 1
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val widths =
                remember(syntax, node, configuration) {
                    val density = context.resources.displayMetrics.density
                    val paint =
                        TextPaint().apply {
                            textSize =
                                DocumentTypography.scaledDp(
                                    context,
                                    DocumentTypography.Subheadline,
                                ) * density
                            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
                        }
                    (0 until columns).map { column ->
                        ((rows.maxOfOrNull { row ->
                                cells(row).getOrNull(column)?.let {
                                    paint.measureText(syntax.text(it).trim())
                                } ?: 0f
                            } ?: 0f) / density + 24f)
                            .coerceAtLeast(32f)
                    }
                }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = UiSpacing.md)
                ) {
                    rows.forEach { row ->
                        if (row.type == "table_separator")
                            HorizontalDivider(Modifier.width(widths.sum().dp))
                        else
                            Row {
                                for (column in 0 until columns) Box(
                                    Modifier.width(widths[column].dp)
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    cells(row).getOrNull(column)?.let {
                                        RichOrgText(
                                            syntax,
                                            it,
                                            model,
                                            editor.file.path,
                                            textSize = DocumentTypography.Subheadline,
                                            query = searchQuery,
                                            readOnly = readOnly,
                                            trimSpaces = true,
                                            fontWeight =
                                                if (
                                                    row == rows.firstOrNull() &&
                                                        rows.getOrNull(1)?.type == "table_separator"
                                                )
                                                    600
                                                else 400,
                                        )
                                    } ?: Text(" ", style = DocumentTypography.subheadline)
                                }
                            }
                    }
                }
            }
        }
        "horizontal_rule" -> HorizontalDivider()
        "list" ->
            Column(verticalArrangement = Arrangement.spacedBy(if (readOnly) UiSpacing.xs else 0.dp)) {
                syntax
                    .children(node)
                    .filter { it.type == "list_item" }
                    .forEach { child ->
                        PreviewBlock(
                            model,
                            editor,
                            syntax,
                            child,
                            searchQuery,
                            collapsed,
                            keys,
                            openItem,
                            complete,
                            onToggle,
                            onDrop,
                            onTimestamp,
                            onState,
                            readOnly,
                        )
                    }
            }
        "list_item" -> {
            val item = node
            val content = syntax.children(item).firstOrNull { it.type == "list_item_content" }
            val rawContent = content?.let(syntax::text).orEmpty()
            val log =
                if (readOnly)
                    Regex(
                            "^\\s*State\\s+\"([^\"]+)\"(?:\\s+from\\s+\"([^\"]*)\")?\\s*(.*)$",
                            RegexOption.DOT_MATCHES_ALL,
                        )
                        .matchEntire(rawContent)
                else null
            if (log != null && content != null) {
                val states =
                    listOf(log.groupValues[2], log.groupValues[1])
                        .filter { it.isNotEmpty() }
                        .joinToString(" → ") { document.workflow.style(it).label.ifEmpty { it } }
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                        contentDescription = states
                    },
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm),
                    verticalAlignment = Alignment.Top,
                ) {
                    val previous = log.groupValues[2]
                    if (previous.isNotEmpty()) {
                        WorkflowIcon(document.workflow, previous)
                        Icon(Icons.Default.ArrowForward, null, Modifier.size(UiSizes.iconSmall))
                    }
                    val current = log.groupValues[1]
                    WorkflowIcon(document.workflow, current)
                    Box(Modifier.weight(1f)) {
                        RichOrgText(
                            syntax,
                            content,
                            model,
                            editor.file.path,
                            query = searchQuery,
                            textSize = DocumentTypography.Subheadline,
                            readOnly = true,
                            visibleStart =
                                content.start +
                                    SourceText(rawContent).byteOffset(log.groups[3]!!.range.first),
                            trimSpaces = true,
                        )
                    }
                }
                return
            }
            val checkbox = syntax.children(item).firstOrNull { it.type == "checkbox" }
            var changing by remember(source) { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val checked = checkbox?.let { syntax.text(it).contains('X', true) } == true
            val marker = syntax.children(item).firstOrNull { it.type.endsWith("list_marker") }
            val indentation =
                marker
                    ?.let {
                        syntax.text(it).takeWhile { char -> char == ' ' || char == '\t' }.length
                    }
                    ?.coerceAtMost(12) ?: 0
            val textInset =
                ((previewCheckboxHeight.value -
                        DocumentTypography.scaledDp(
                            LocalContext.current,
                            DocumentTypography.Subheadline,
                        ) * 1.2f) / 2)
                    .coerceAtLeast(0f)
                    .dp
            Row(Modifier.padding(start = (indentation * 4).dp)) {
                if (checkbox != null && !readOnly)
                    CompositionLocalProvider(
                        LocalMinimumInteractiveComponentSize provides previewCheckboxHeight
                    ) {
                        Checkbox(
                            checked,
                            onCheckedChange = { checked ->
                                changing = true
                                val change = model.action {
                                    val next =
                                        model.repository.engine.toggleCheckbox(
                                            document,
                                            checkbox.start,
                                            checked,
                                        )
                                    model.repository.save(editor.file.id, next, editor.baseline)
                                }
                                scope.launch {
                                    change.join()
                                    changing = false
                                }
                            },
                            enabled = !changing,
                            modifier =
                                Modifier.width(48.dp)
                                    .height(previewCheckboxHeight)
                                    .scale(0.9f)
                                    .semantics {
                                        contentDescription =
                                            syntax
                                                .text(item)
                                                .lineSequence()
                                                .first()
                                                .trim()
                                                .replace(Regex("^[-+*] \\[[ Xx-]\\]\\s*"), "")
                                    },
                        )
                    }
                else
                    Text(
                        checkbox?.let(syntax::text)?.trim()
                            ?: marker?.let(syntax::text)?.trim().orEmpty().let {
                                when (it) {
                                    "-" -> "•"
                                    "+" -> "◦"
                                    "*" -> "▪"
                                    else -> it
                                }
                            },
                        Modifier.alignByBaseline()
                            .widthIn(min = 20.dp)
                            .padding(end = UiSpacing.sm)
                            .testTag("preview.list.marker.${item.start}"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        style =
                            DocumentTypography.subheadline.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            ),
                    )
                Box(
                    Modifier.weight(1f)
                        .alignByBaseline()
                        .padding(top = if (checkbox != null && !readOnly) textInset else 0.dp)
                ) {
                    RichOrgText(
                        syntax,
                        if (readOnly) content ?: item else item,
                        model,
                        editor.file.path,
                        omitCheckbox = true,
                        textSize = DocumentTypography.Subheadline,
                        query = searchQuery,
                        completed = checked && !readOnly,
                        readOnly = readOnly,
                        trimSpaces = true,
                    )
                }
            }
        }
        "keyword" ->
            if (source.startsWith("#+title:", true))
                Text(
                    highlightedText(source.substringAfter(':').trim(), searchQuery),
                    style = DocumentTypography.title,
                    modifier = Modifier.semantics { heading() },
                )
            else if (source.startsWith("#+caption:", true))
                syntax
                    .children(node)
                    .firstOrNull { it.type == "keyword_value" }
                    ?.let {
                        RichOrgText(
                            syntax,
                            it,
                            model,
                            editor.file.path,
                            textSize = DocumentTypography.Subheadline,
                            query = searchQuery,
                            readOnly = readOnly,
                        )
                    }
            else
                Text(
                    highlightedText(source.trimEnd(), searchQuery),
                    style = DocumentTypography.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        "custom_block",
        "dynamic_block" -> {
            val custom = node.type == "custom_block"
            Surface(
                color =
                    if (custom) MaterialTheme.colorScheme.surfaceContainerLow
                    else MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(if (custom) 10.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.xs),
                ) {
                    if (custom)
                        Text(
                            highlightedText(
                                source.lineSequence().first().trim().drop(8),
                                searchQuery,
                            ),
                            style =
                                DocumentTypography.caption.copy(
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    syntax
                        .children(node)
                        .filterNot {
                            it.type.endsWith("_begin") ||
                                it.type.endsWith("_end") ||
                                it.type == "blank_line"
                        }
                        .forEachIndexed { index, child ->
                            val spacing =
                                if (!custom) if (child.type == "paragraph_group") 8 else 0
                                else
                                    when (child.type) {
                                        "paragraph_group" -> 8
                                        "list",
                                        "list_item" -> 0
                                        else -> 2
                                    }
                            Box(Modifier.padding(top = if (index == 0) 0.dp else spacing.dp)) {
                                PreviewBlock(
                                    model,
                                    editor,
                                    syntax,
                                    child,
                                    searchQuery,
                                    collapsed,
                                    keys,
                                    openItem,
                                    complete,
                                    onToggle,
                                    onDrop,
                                    onTimestamp,
                                    onState,
                                    readOnly || !custom,
                                )
                            }
                        }
                }
            }
        }
        "clock" -> ClockPreview(source)
        "paragraph",
        "paragraph_group" -> {
            val timestamp =
                syntax.descendants(node).firstOrNull {
                    it.type in
                        listOf("timestamp_range", "active_timestamp", "inactive_timestamp") &&
                        syntax.text(it).trim() == source.trim()
                }
            if (
                !readOnly &&
                    timestamp != null &&
                    PlanningDraft.parse(syntax.text(timestamp)) != null
            )
                TimestampControl(syntax.text(timestamp)) { onTimestamp(timestamp) }
            else
                RichOrgText(
                    syntax,
                    node,
                    model,
                    editor.file.path,
                    textSize = bodySize,
                    query = searchQuery,
                    readOnly = readOnly,
                    foregroundOverride =
                        MaterialTheme.colorScheme.onSurfaceVariant.toArgb().takeIf { readOnly },
                )
        }
        "quote_block",
        "verse_block",
        "center_block" -> {
            val quote = node.type == "quote_block"
            val centered = node.type == "center_block"
            val tint = MaterialTheme.colorScheme.primary
            val color = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
            val content =
                syntax.children(node).filterNot {
                    it.type.endsWith("_begin") ||
                        it.type.endsWith("_end") ||
                        it.type == "blank_line"
                }
            Column(
                Modifier.fillMaxWidth()
                    .then(
                        if (quote)
                            Modifier.drawBehind {
                                    drawRect(
                                        tint,
                                        size =
                                            androidx.compose.ui.geometry.Size(
                                                2.dp.toPx(),
                                                size.height,
                                            ),
                                    )
                                }
                                .padding(start = 10.dp)
                        else Modifier
                    ),
                horizontalAlignment =
                    if (centered) Alignment.CenterHorizontally else Alignment.Start,
                verticalArrangement =
                    Arrangement.spacedBy(if (node.type == "verse_block") UiSpacing.xs else UiSpacing.md),
            ) {
                content.forEach { child ->
                    if (child.type == "latex_block") Formula(syntax.text(child))
                    else
                        RichOrgText(
                            syntax,
                            child,
                            model,
                            editor.file.path,
                            textSize =
                                if (quote) DocumentTypography.Subheadline
                                else DocumentTypography.Body,
                            query = searchQuery,
                            readOnly = readOnly,
                            centered = centered,
                            foregroundOverride = color.takeIf { quote },
                            italic = quote,
                        )
                }
            }
        }
        "footnote_definition" ->
            RichOrgText(
                syntax,
                node,
                model,
                editor.file.path,
                textSize = DocumentTypography.Footnote,
                query = searchQuery,
                readOnly = readOnly,
            )
        else ->
            RichOrgText(
                syntax,
                node,
                model,
                editor.file.path,
                textSize = bodySize,
                query = searchQuery,
                readOnly = readOnly,
            )
    }
}

private fun latex(source: String): String {
    val value = source.trim()
    return when {
        value.startsWith("$$") && value.endsWith("$$") -> value.drop(2).dropLast(2)
        value.startsWith("\\[") && value.endsWith("\\]") ||
            value.startsWith("\\(") && value.endsWith("\\)") -> value.drop(2).dropLast(2)
        value.startsWith('$') && value.endsWith('$') -> value.drop(1).dropLast(1)
        else -> value
    }
}

@Composable
private fun Formula(source: String) {
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val fontSize =
        remember(context, configuration) {
            DocumentTypography.scaledDp(context, DocumentTypography.Body) * 1.08f
        }
    val density = LocalDensity.current
    val expression = remember(source) { latex(source) }
    val rendered by
        produceState<Result<RaTeXRenderer>?>(null, context, expression, fontSize, color) {
            value =
                try {
                    Result.success(
                        withContext(Dispatchers.Default) {
                            RaTeXFontLoader.ensureLoaded(context)
                            RaTeXRenderer(
                                RaTeXEngine.parse(expression, displayMode = true, color = color),
                                fontSize * context.resources.displayMetrics.density,
                                RaTeXFontLoader::getTypeface,
                            )
                        }
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(error)
                }
        }
    Box(
        Modifier.fillMaxWidth()
            .padding(vertical = UiSpacing.xs)
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = expression }
    ) {
        val renderer = rendered?.getOrNull()
        if (renderer != null) {
            // Publish dimensions and drawing together so LazyColumn remeasures the
            // row when rendering finishes, without relying on View.requestLayout.
            val width = with(density) { kotlin.math.ceil(renderer.widthPx).toDp() }
            val height = with(density) { kotlin.math.ceil(renderer.totalHeightPx).toDp() }
            Canvas(Modifier.size(width, height)) { renderer.draw(drawContext.canvas.nativeCanvas) }
        } else if (rendered?.isFailure == true) Text(expression, style = DocumentTypography.body)
        else Spacer(Modifier.height(fontSize.dp))
    }
}

@Composable
private fun RichOrgText(
    syntax: SyntaxDocument,
    node: OrgNode,
    model: OrgendaViewModel,
    path: String,
    omitCheckbox: Boolean = false,
    textSize: Float = DocumentTypography.Body,
    query: String = "",
    visibleStart: Int = node.start,
    visibleEnd: Int = node.end,
    splitImages: Boolean = true,
    readOnly: Boolean = false,
    completed: Boolean = false,
    centered: Boolean = false,
    foregroundOverride: Int? = null,
    activate: (() -> Unit)? = null,
    trimSpaces: Boolean = false,
    italic: Boolean = false,
    fontWeight: Int = 400,
    headingDrag: HeadingDrag? = null,
) {
    val currentEditor by model.editor.collectAsState()
    val document = currentEditor?.document?.takeIf { it.text == syntax.source.text }
    val images =
        remember(syntax, node, splitImages, visibleStart, visibleEnd) {
            if (splitImages)
                (listOf(node) + syntax.descendants(node))
                    .mapNotNull { ImageReference.from(syntax, it) }
                    .filter { it.start >= visibleStart && it.end <= visibleEnd }
            else emptyList()
        }
    if (images.isNotEmpty() && document != null) {
        Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.sm)) {
            var cursor = visibleStart
            images.forEach { image ->
                if (image.start > cursor && syntax.source.slice(cursor, image.start).isNotBlank())
                    RichOrgText(
                        syntax,
                        node,
                        model,
                        path,
                        omitCheckbox,
                        textSize,
                        query,
                        cursor,
                        image.start,
                        false,
                        readOnly,
                        completed,
                        centered,
                        foregroundOverride,
                        activate,
                        trimSpaces,
                        italic,
                        fontWeight,
                        headingDrag,
                    )
                PreviewImage(model, document, image)
                cursor = image.end
            }
            if (cursor < visibleEnd && syntax.source.slice(cursor, visibleEnd).isNotBlank())
                RichOrgText(
                    syntax,
                    node,
                    model,
                    path,
                    omitCheckbox,
                    textSize,
                    query,
                    cursor,
                    visibleEnd,
                    false,
                    readOnly,
                    completed,
                    centered,
                    foregroundOverride,
                    activate,
                    trimSpaces,
                    italic,
                    fontWeight,
                    headingDrag,
                )
        }
        return
    }
    val context = LocalContext.current
    var timestampEditorKey by
        rememberSaveable(path, node.start, node.end) { mutableStateOf<String?>(null) }
    val foreground = foregroundOverride ?: MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val searchHighlight = uiColors().searchHighlight
    val configuration = LocalConfiguration.current
    val size =
        remember(context, textSize, configuration) {
            DocumentTypography.scaledDp(context, textSize)
        }
    val rich by
        produceState<SpannableStringBuilder?>(
            null,
            syntax,
            node.start,
            foreground,
            linkColor,
            searchHighlight,
            size,
            query,
            visibleStart,
            visibleEnd,
            readOnly,
            completed,
            trimSpaces,
        ) {
            value =
                withContext(Dispatchers.Default) {
                    val output = SpannableStringBuilder()
                    fun appendSource(start: Int, end: Int) {
                        if (minOf(end, visibleEnd) > maxOf(start, visibleStart))
                            output.append(
                                syntax.source.slice(
                                    maxOf(start, visibleStart),
                                    minOf(end, visibleEnd),
                                )
                            )
                    }
                    suspend fun append(current: OrgNode) {
                        if (current.end <= visibleStart || current.start >= visibleEnd) return
                        if (current.start < visibleStart || current.end > visibleEnd) {
                            val children = syntax.children(current)
                            if (children.isEmpty()) appendSource(current.start, current.end)
                            else {
                                var cursor = current.start
                                for (child in children) {
                                    appendSource(cursor, child.start)
                                    append(child)
                                    cursor = child.end
                                }
                                appendSource(cursor, current.end)
                            }
                            return
                        }
                        val raw = syntax.text(current)
                        val start = output.length
                        val type = current.type
                        if (type == "paragraph_group") {
                            for (line in syntax.children(current)) {
                                val lineStart = output.length
                                append(line)
                                while (
                                    output.length > lineStart && output.last().isWhitespace()
                                ) output.delete(output.length - 1, output.length)
                                var first = lineStart
                                while (
                                    first < output.length && output[first].isWhitespace()
                                ) first++
                                output.delete(lineStart, first)
                                if (lineStart > start && output.length > lineStart)
                                    output.insert(lineStart, " ")
                            }
                            return
                        }
                        if (
                            type in
                                listOf(
                                    "timestamp_range",
                                    "active_timestamp",
                                    "inactive_timestamp",
                                ) && PlanningDraft.parse(raw) != null
                        ) {
                            output.append(readableTimestamp(context, raw, compact = true))
                            if (readOnly) {
                                output.setSpan(
                                    ForegroundColorSpan(linkColor),
                                    start,
                                    output.length,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                )
                                return
                            }
                            output.setSpan(
                                object : ClickableSpan() {
                                    override fun onClick(widget: android.view.View) {
                                        timestampEditorKey = current.timestampKey()
                                    }

                                    override fun updateDrawState(ds: TextPaint) {
                                        ds.color = linkColor
                                        ds.isUnderlineText = false
                                    }
                                },
                                start,
                                output.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                            return
                        }
                        if (type.endsWith("_begin") || type.endsWith("_end")) return
                        if (type == "checkbox" && omitCheckbox) return
                        if (
                            type in listOf("unordered_list_marker", "ordered_list_marker") &&
                                omitCheckbox
                        )
                            return
                        if (type == "latex_fragment") {
                            val span = runCatching {
                                RaTeXSpan.create(
                                    context,
                                    latex(raw),
                                    size,
                                    foreground,
                                    displayMode = false,
                                )
                            }
                                .getOrNull()
                            if (span != null) {
                                output.append(latex(raw))
                                output.setSpan(
                                    span,
                                    start,
                                    output.length,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                )
                            } else output.append(raw)
                            return
                        }
                        if (type in listOf("link", "plain_link", "angle_link")) {
                            val match =
                                Regex("\\[\\[([^]]+)\\](?:\\[([^]]*)\\])?\\]").matchEntire(raw)
                            if (match != null || type != "link") {
                                val target =
                                    match?.groupValues?.get(1)
                                        ?: if (type == "angle_link") raw.drop(1).dropLast(1)
                                        else raw
                                output.append(
                                    match?.groupValues?.get(2)?.ifEmpty { target } ?: target
                                )
                                output.setSpan(
                                    object : ClickableSpan() {
                                        override fun onClick(widget: android.view.View) {
                                            if (
                                                target.startsWith("https://") ||
                                                    target.startsWith("http://") ||
                                                    target.startsWith("mailto:")
                                            )
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(
                                                            Intent.ACTION_VIEW,
                                                            Uri.parse(target),
                                                        )
                                                    )
                                                }
                                            else {
                                                model.openOrgLink(path, target)
                                            }
                                        }

                                        override fun updateDrawState(ds: TextPaint) {
                                            ds.color = linkColor
                                            ds.isUnderlineText = true
                                        }
                                    },
                                    start,
                                    output.length,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                )
                                return
                            }
                        }
                        if (
                            type in
                                listOf(
                                    "target",
                                    "radio_target",
                                    "statistics_cookie",
                                    "description_tag",
                                    "footnote_reference",
                                    "inline_footnote",
                                    "footnote_definition_marker",
                                )
                        ) {
                            val value =
                                when (type) {
                                    "target",
                                    "radio_target" -> "⌖ " + raw.trim('<', '>')
                                    "statistics_cookie" -> raw.removeSurrounding("[", "]")
                                    "description_tag" -> raw.trim().removeSuffix("::").trim()
                                    else ->
                                        "[" +
                                            raw.trim()
                                                .removePrefix("[fn:")
                                                .removeSuffix("]")
                                                .trim(':') +
                                            "]"
                                }
                            output.append(value)
                            output.setSpan(
                                ForegroundColorSpan(linkColor),
                                start,
                                output.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                            if (type in listOf("statistics_cookie", "description_tag"))
                                output.setSpan(
                                    StyleSpan(Typeface.BOLD),
                                    start,
                                    output.length,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                )
                            return
                        }
                        if (
                            type in
                                listOf(
                                    "bold",
                                    "italic",
                                    "underline",
                                    "strikethrough",
                                    "code",
                                    "verbatim",
                                )
                        ) {
                            val body = raw.substring(1, raw.length - 1)
                            output.append(body)
                            if (type !in listOf("code", "verbatim")) {
                                Regex("\\\\\\(.*?\\\\\\)|\\\\\\[.*?\\\\\\]|\\\$[^\\\$\\n]+\\\$")
                                    .findAll(body)
                                    .forEach { formula ->
                                        runCatching {
                                            RaTeXSpan.create(
                                                context,
                                                latex(formula.value),
                                                size,
                                                foreground,
                                                displayMode = false,
                                            )
                                        }
                                            .getOrNull()
                                            ?.let {
                                                output.setSpan(
                                                    it,
                                                    start + formula.range.first,
                                                    start + formula.range.last + 1,
                                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                                )
                                            }
                                    }
                            }
                            val span: Any =
                                when (type) {
                                    "bold" -> StyleSpan(Typeface.BOLD)
                                    "italic" -> StyleSpan(Typeface.ITALIC)
                                    "underline" -> UnderlineSpan()
                                    "strikethrough" -> StrikethroughSpan()
                                    else -> TypefaceSpan("monospace")
                                }
                            output.setSpan(
                                span,
                                start,
                                output.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        } else {
                            val children = syntax.children(current)
                            if (children.isEmpty()) output.append(raw)
                            else {
                                var byte = current.start
                                for (child in children) {
                                    if (child.start > byte)
                                        output.append(syntax.source.slice(byte, child.start))
                                    append(child)
                                    byte = child.end
                                }
                                if (byte < current.end)
                                    output.append(syntax.source.slice(byte, current.end))
                            }
                        }
                    }
                    append(node)
                    while (
                        output.isNotEmpty() && (output.last() == '\n' || output.last() == '\r')
                    ) output.delete(output.length - 1, output.length)
                    if (trimSpaces) {
                        while (output.isNotEmpty() && output.first().isWhitespace()) output.delete(
                            0,
                            1,
                        )
                        while (output.isNotEmpty() && output.last().isWhitespace()) output.delete(
                            output.length - 1,
                            output.length,
                        )
                    }
                    if (completed && output.isNotEmpty())
                        output.setSpan(
                            StrikethroughSpan(),
                            0,
                            output.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    searchHighlight.applyTo(output, query)
                    output
                }
        }
    MeasuredPreviewText(
        rich ?: syntax.source.slice(visibleStart, visibleEnd).trimEnd(),
        Modifier.fillMaxWidth(),
    ) {
        it.setTextColor(foreground)
        it.setLinkTextColor(linkColor)
        it.textSize = textSize
        it.typeface = Typeface.create(Typeface.DEFAULT, fontWeight, italic)
        it.setLineSpacing(6f * it.resources.displayMetrics.density, 1f)
        if (activate != null || headingDrag != null) {
            it.setTextIsSelectable(false)
            it.setOnClickListener { activate?.invoke() }
        } else {
            it.setOnClickListener(null)
            it.setTextIsSelectable(true)
        }
        it.setOnLongClickListener(if (headingDrag != null) android.view.View.OnLongClickListener { view ->
            view.startOrgDrag("heading", headingDrag)
        } else null)
        it.movementMethod = LinkMovementMethod.getInstance()
        it.gravity =
            if (centered) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START
    }
    timestampEditorKey?.let { requested ->
        syntax.nodes
            .firstOrNull { it.timestampKey() == requested }
            ?.let { TimestampSheet(model, path, syntax, it) { timestampEditorKey = null } }
    }
}

@Composable
private fun CodeBlock(source: String, language: String, query: String, label: String) {
    val colors = uiColors()
    val foreground = colors.sourceForeground
    val accent = colors.sourceAccent
    val secondary = colors.sourceSecondary
    val tertiary = colors.sourceCodeAccent
    val muted = colors.sourceMuted
    val searchHighlight = colors.searchHighlight
    val text by
        produceState<CharSequence>(
            source,
            source,
            language,
            accent,
            secondary,
            tertiary,
            muted,
            searchHighlight,
            query,
        ) {
            value =
                withContext(Dispatchers.Default) {
                    SpannableString(
                            CodeHighlight.text(source, language, accent, secondary, tertiary, muted)
                        )
                        .apply { searchHighlight.applyTo(this, query) }
                }
        }
    Surface(
        modifier = Modifier.padding(vertical = UiSpacing.xs),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(UiSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.xs),
        ) {
            if (label.isNotEmpty())
                Text(
                    label,
                    style =
                        DocumentTypography.caption.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        ),
                )
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                MeasuredPreviewText(text) {
                    it.typeface = Typeface.MONOSPACE
                    it.textSize = DocumentTypography.Footnote
                    it.setTextColor(foreground)
                }
            }
        }
    }
}

@Composable
private fun highlightedText(
    source: String,
    query: String,
): androidx.compose.ui.text.AnnotatedString {
    val colors = uiColors().searchHighlight
    return androidx.compose.ui.text.buildAnnotatedString {
        append(source)
        SearchText.ranges(source, query).forEach { match ->
            addStyle(
                androidx.compose.ui.text.SpanStyle(background = colors.background, color = colors.foreground),
                match.first,
                match.last + 1,
            )
        }
    }
}

private fun OrgNode.timestampKey() = "$type:$start:$end"
