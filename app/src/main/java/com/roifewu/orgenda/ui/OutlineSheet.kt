@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roifewu.orgenda.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.*
import com.roifewu.orgenda.core.*
import java.time.LocalDate
import kotlin.math.roundToInt

private val OutlineFilterSaver =
    listSaver<OutlineFilter, String>(
        save = {
            listOf(
                it.scheduledFrom?.toString().orEmpty(),
                it.scheduledTo?.toString().orEmpty(),
                it.deadlineFrom?.toString().orEmpty(),
                it.deadlineTo?.toString().orEmpty(),
            ) + it.states
        },
        restore = { values ->
            fun day(index: Int) = values[index].takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
            OutlineFilter(values.drop(4).toSet(), day(0), day(1), day(2), day(3))
        },
    )
private val OutlineExpandedSaver =
    listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

@Composable
fun OutlineSheet(model: OrgendaViewModel, editor: EditorState, close: () -> Unit) {
    val document = editor.document ?: return
    ModalPresence()
    var filter by
        rememberSaveable(editor.session, stateSaver = OutlineFilterSaver) {
            mutableStateOf(OutlineFilter())
        }
    var showFilter by rememberSaveable(editor.session) { mutableStateOf(false) }
    var expanded by
        rememberSaveable(editor.session, stateSaver = OutlineExpandedSaver) {
            mutableStateOf(emptySet<String>())
        }
    val keys = remember(document) { Outline.keys(document) }
    var handledMove by
        rememberSaveable(editor.session) { mutableStateOf(editor.headingMove?.text?.sha256()) }
    LaunchedEffect(document, editor.headingMove) {
        val moved = editor.headingMove
        val revision = moved?.text?.sha256()
        if (moved != null && moved.text == document.text && revision != handledMove) {
            expanded =
                expanded.mapNotNull(editor.headingMoveKeys::get).toSet() +
                    document.headings
                        .filter { editor.jumpByte > it.start && editor.jumpByte < it.subtreeEnd }
                        .map { keys.getValue(it.start) }
            handledMove = revision
        }
    }
    val filtered =
        remember(document, filter, expanded) {
            filter.apply(document.headings).filter { heading ->
                filter.active ||
                    document.headings.none { parent ->
                        heading.start > parent.start &&
                            heading.start < parent.subtreeEnd &&
                            keys.getValue(parent.start) !in expanded
                    }
            }
        }
    val listState = rememberLazyListState()
    val drag = remember(editor.session) { OutlineDragState() }
    val drop by remember(filtered, editor.session) {
        derivedStateOf { outlineDropAt(drag, listState, filtered, editor.session) }
    }
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val childContainer = uiColors().headingDropContainer
    val edgePixels = with(LocalDensity.current) { UiSpacing.edge.toPx() }
    var moveError by remember(editor.session) { mutableStateOf<String?>(null) }
    fun move(source: OrgHeading, target: OrgHeading, placement: HeadingPlacement) {
        moveError = null
        model.moveHeading(source, target, placement, onFailure = {
            moveError = it.message ?: context.label("operation_failed")
        })
    }
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = UiSpacing.edge),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr("outline"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { showFilter = true },
                modifier = Modifier.testTag("outline.filter"),
            ) {
                Icon(Icons.Default.FilterList, tr("filter"))
            }
            TextButton(shape = MaterialTheme.shapes.medium, onClick = close) { Text(tr("close")) }
        }
        if (filter.active)
            Row(Modifier.padding(horizontal = UiSpacing.edge)) {
                Text(
                    "${document.headings.count(filter::matches)} " + tr("matched_headings"),
                    Modifier.weight(1f),
                )
                TextButton(shape = MaterialTheme.shapes.medium, onClick = { filter = OutlineFilter() }) { Text(tr("clear")) }
            }
        if (filtered.isEmpty())
            Text(
                tr("no_results"),
                Modifier.padding(horizontal = UiSpacing.edge, vertical = UiSpacing.xl),
            )
        moveError?.let {
            Text(
                it,
                Modifier.padding(UiSpacing.edge).testTag("outline.moveError")
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
            )
        }
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.8f).clipToBounds()) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("outline.list").outlineDrag(
                    drag, listState, filtered, editor.session,
                    document.text, ::move,
                ),
                state = listState,
                userScrollEnabled = drag.source == null,
            ) {
                items(filtered, key = { it.start }) { heading ->
                    val children =
                        document.headings.any {
                            it.start > heading.start && it.start < heading.subtreeEnd
                        }
                    val headingKey = keys.getValue(heading.start)
                    val isExpanded = filter.active || headingKey in expanded
                    val placement = drop?.takeIf { it.target.start == heading.start }?.placement
                    val moves = headingMoveTargets(document, heading)
                    Row(
                        Modifier.fillMaxWidth()
                            .testTag("heading.drop.${heading.start}")
                            .semantics {
                                placement?.let {
                                    stateDescription = context.label("placement_${it.name.lowercase()}")
                                }
                            }
                            .drawBehind {
                                placement?.let {
                                    if (it == HeadingPlacement.CHILD) drawRect(childContainer)
                                    val line = UiSizes.activeBorder.toPx()
                                    val y = if (it == HeadingPlacement.BEFORE) line / 2 else size.height - line / 2
                                    val inset = if (it == HeadingPlacement.CHILD) edgePixels else 0f
                                    drawLine(primary, Offset(inset, y), Offset(size.width, y), line)
                                }
                            }
                            .graphicsLayer { alpha = if (drag.source?.item?.start == heading.start) 0.4f else 1f }
                            .padding(horizontal = UiSpacing.edge)
                            .padding(start = UiSpacing.outlineIndent * (heading.level - 1).coerceAtMost(8)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (children)
                            IconButton(
                                enabled = !filter.active,
                                onClick = {
                                    expanded =
                                        if (headingKey in expanded) expanded - headingKey
                                        else expanded + headingKey
                                },
                                modifier = Modifier.testTag("outline.toggle.${heading.start}"),
                            ) {
                                Icon(
                                    if (isExpanded) Icons.Default.ExpandMore
                                    else Icons.Default.ChevronRight,
                                    tr(if (isExpanded) "collapse" else "expand") + " " + heading.title,
                                )
                            }
                        else Spacer(Modifier.width(UiSizes.touchTarget))
                        TextButton(
                            shape = MaterialTheme.shapes.medium,
                            onClick = {
                                if (model.editor.value?.text == document.text) {
                                    model.jump(heading.start, focusEditor = true)
                                    close()
                                }
                            },
                            modifier =
                                Modifier.weight(1f)
                                    .testTag("outline.heading.${heading.start}")
                                    .semantics {
                                        customActions = moves.mapNotNull { (label, target, position) ->
                                            target?.let {
                                                CustomAccessibilityAction(context.label(label)) {
                                                    move(heading, it, position)
                                                    true
                                                }
                                            }
                                        }
                                    },
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    heading.title,
                                    style =
                                        DocumentTypography.body.copy(
                                            fontWeight =
                                                if (heading.level == 1)
                                                    androidx.compose.ui.text.font.FontWeight.SemiBold
                                                else androidx.compose.ui.text.font.FontWeight.Normal
                                        ),
                                )
                                if (heading.state.isNotEmpty())
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(UiSpacing.controlGap),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        WorkflowIcon(heading)
                                        Text(
                                            heading.workflow.style(heading.state).label.ifEmpty {
                                                heading.state
                                            },
                                            color = workflowColor(heading),
                                            style = DocumentTypography.subheadline,
                                        )
                                    }
                            }
                        }
                    }
                }
            }
            drag.source?.let { source ->
                Surface(
                    Modifier.offset {
                            IntOffset(0, (drag.pointer.y - drag.rowHeight / 2f).roundToInt())
                        }
                        .fillMaxWidth().padding(horizontal = UiSpacing.edge)
                        .clearAndSetSemantics { },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 6.dp,
                ) {
                    Column(Modifier.padding(UiSpacing.md)) {
                        Text(source.item.title, style = DocumentTypography.body)
                        drop?.let {
                            Text(
                                tr("placement_${it.placement.name.lowercase()}") + ": " + it.target.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
    if (showFilter) {
        ModalPresence()
        ModalBottomSheet(
            onDismissRequest = { showFilter = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(UiSpacing.edge)) {
                Text(tr("filter"), style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(UiSpacing.controlGap)) {
                    document.workflow.tokens.forEach { token ->
                        FilterChip(
                            token in filter.states,
                            {
                                filter =
                                    filter.copy(
                                        states =
                                            if (token in filter.states) filter.states - token
                                            else filter.states + token
                                    )
                            },
                            label = {
                                Text(
                                    document.workflow.style(token).label.ifEmpty { token },
                                    style = DocumentTypography.subheadline,
                                )
                            },
                            modifier = Modifier.testTag("outline.filter.state.$token"),
                        )
                    }
                }
                fun value(day: LocalDate?) =
                    PlanningValue(day != null, (day ?: LocalDate.now()).atStartOfDay(), false, "")
                Text(tr("scheduled"))
                PlanningField(
                    "start",
                    value(filter.scheduledFrom),
                    showRepeat = false,
                    showTimeToggle = false,
                ) {
                    filter =
                        filter.copy(
                            scheduledFrom = it.date.toLocalDate().takeIf { _ -> it.enabled }
                        )
                }
                PlanningField(
                    "end",
                    value(filter.scheduledTo),
                    showRepeat = false,
                    showTimeToggle = false,
                ) {
                    filter =
                        filter.copy(scheduledTo = it.date.toLocalDate().takeIf { _ -> it.enabled })
                }
                Text(tr("deadline"))
                PlanningField(
                    "start",
                    value(filter.deadlineFrom),
                    showRepeat = false,
                    showTimeToggle = false,
                ) {
                    filter =
                        filter.copy(deadlineFrom = it.date.toLocalDate().takeIf { _ -> it.enabled })
                }
                PlanningField(
                    "end",
                    value(filter.deadlineTo),
                    showRepeat = false,
                    showTimeToggle = false,
                ) {
                    filter =
                        filter.copy(deadlineTo = it.date.toLocalDate().takeIf { _ -> it.enabled })
                }
                Row {
                    TextButton(shape = MaterialTheme.shapes.medium, onClick = { filter = OutlineFilter() }) { Text(tr("reset")) }
                    TextButton(
                        shape = MaterialTheme.shapes.medium,
                        onClick = { showFilter = false },
                        modifier = Modifier.testTag("outline.filter.close"),
                    ) {
                        Text(tr("close"))
                    }
                }
            }
        }
    }
}

internal fun headingMoveTargets(
    document: OrgDocument,
    item: OrgHeading,
): List<Triple<String, OrgHeading?, HeadingPlacement>> {
    val parent =
        document.headings.lastOrNull {
            it.start < item.start && it.subtreeEnd > item.start && it.level < item.level
        }
    fun parent(start: Int) =
        document.headings
            .lastOrNull { it.start < start && it.subtreeEnd > start && it.level < item.level }
            ?.start
    val siblings =
        document.headings.filter { it.level == item.level && parent(it.start) == parent?.start }
    val previous = siblings.lastOrNull { it.start < item.start }
    val next = siblings.firstOrNull { it.start > item.start }
    return listOf(
        Triple("up", previous, HeadingPlacement.BEFORE),
        Triple("down", next, HeadingPlacement.AFTER),
        Triple("indent", previous, HeadingPlacement.CHILD),
        Triple("outdent", parent, HeadingPlacement.AFTER),
    )
}
