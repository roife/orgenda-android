@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.roifewu.orgenda.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.WorkspaceSnapshot
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CalendarDay(
    val date: LocalDate,
    val rows: List<OrgHeading>,
    val journals: List<JournalEntry>,
    val allDay: Boolean,
    val headerIndex: Int,
)

@Composable
fun CalendarScreen(
    snapshot: WorkspaceSnapshot,
    selected: LocalDate,
    select: (LocalDate) -> Unit,
    open: (OrgHeading) -> Unit,
    toggle: (OrgHeading) -> Unit,
    journal: () -> Unit,
    showFile: (String, Int) -> Unit,
    capture: () -> Unit,
    showOverdue: () -> Unit,
    state: CalendarScreenState = rememberCalendarScreenState(selected),
    showTopBar: Boolean = true,
    reschedule: (OrgHeading, LocalDate) -> Unit,
) {
    val context = LocalContext.current
    val currentDay = LocalToday.current
    val today = currentDay.date
    val fontScale = LocalDensity.current.fontScale
    var density by state.density
    var position by state.position
    val latestSnapshot by rememberUpdatedState(snapshot)
    val schedulingSaver = remember {
        Saver<OrgHeading?, List<String>>(
            save = { item ->
                item?.let {
                    listOf(
                        latestSnapshot.workspace?.id.orEmpty(),
                        it.path,
                        it.start.toString(),
                        it.sourceHash,
                    )
                }
            },
            restore = { saved ->
                latestSnapshot
                    .takeIf { it.workspace?.id.orEmpty() == saved[0] }
                    ?.documents
                    ?.get(saved[1])
                    ?.headings
                    ?.firstOrNull { it.start.toString() == saved[2] && it.sourceHash == saved[3] }
            },
        )
    }
    var scheduling by
        rememberSaveable(stateSaver = schedulingSaver) { mutableStateOf<OrgHeading?>(null) }
    var picker by state.picker
    var timelineAnchor by rememberSaveable { mutableStateOf(selected.toString()) }
    var timelineSelection by rememberSaveable { mutableStateOf<String?>(null) }
    var todayRequest by state.todayRequest
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var userScrolling by remember { mutableStateOf(false) }
    val days =
        remember(timelineAnchor) {
            val anchor = LocalDate.parse(timelineAnchor)
            (-30L..365L).map(anchor::plusDays)
        }
    val marks =
        remember(snapshot.revision) {
            snapshot.items
                .filter { !it.done }
                .flatMap {
                    listOfNotNull(it.scheduled, it.deadline, it.event).map { stamp ->
                        stamp.date.toLocalDate()
                    }
                }
                .toSet() + snapshot.journals.map { it.date.toLocalDate() }
        }
    val overdue =
        remember(snapshot.revision, today) { snapshot.items.count { Agenda.overdue(it, today) } }
    val canonical = remember(snapshot.revision) { snapshot.items.associateBy { it.id } }
    val sections by
        produceState<List<CalendarDay>>(emptyList(), snapshot.revision, days, currentDay, overdue) {
            value =
                withContext(Dispatchers.Default) {
                    val entries = snapshot.journals.groupBy { it.date.toLocalDate() }
                    var index = 0
                    days.map { day ->
                        val rows = Agenda.on(snapshot.items, day)
                        val (allDay, timed) =
                            rows.partition {
                                it.kind == "event" && it.agendaStamp?.hasTime == false
                            }
                        val journals =
                            entries[day]
                                .orEmpty()
                                .sortedWith(
                                    compareByDescending<JournalEntry> { it.date }
                                        .thenBy { it.path }
                                        .thenByDescending { it.start }
                                )
                        CalendarDay(day, allDay + timed, journals, allDay.isNotEmpty(), index)
                            .also {
                                index +=
                                    1 +
                                        rows.size +
                                        journals.size +
                                        (if (allDay.isNotEmpty()) 1 else 0) +
                                        (if (day == today && overdue > 0) 1 else 0) +
                                        (if (rows.isEmpty() && journals.isEmpty()) 1 else 0)
                            }
                    }
                }
        }
    val currentSelect by rememberUpdatedState(select)
    LaunchedEffect(selected) {
        if (selected != position.selected) position = position.select(selected)
    }
    LaunchedEffect(dragged, list.isScrollInProgress) {
        if (dragged) userScrolling = true
        if (!list.isScrollInProgress) userScrolling = false
    }
    LaunchedEffect(list, sections) {
        snapshotFlow { list.firstVisibleItemIndex to userScrolling }
            .distinctUntilChanged()
            .collect { (index, scrolling) ->
                if (scrolling)
                    sections
                        .lastOrNull { it.headerIndex <= index }
                        ?.date
                        ?.let { day ->
                            timelineSelection = day.toString()
                            currentSelect(day)
                        }
            }
    }
    LaunchedEffect(selected, todayRequest, days, sections, fontScale) {
        if (selected.toString() == timelineSelection && todayRequest == 0) return@LaunchedEffect
        timelineSelection = null
        if (selected !in days.first()..days.last()) {
            timelineAnchor = selected.toString()
            return@LaunchedEffect
        }
        userScrolling = false
        val index =
            sections.firstOrNull { it.date == selected }?.headerIndex ?: return@LaunchedEffect
        list.scrollToItem(index)
        todayRequest = 0
    }
    val panel: @Composable (Boolean) -> Unit = { wide ->
        CalendarPanel(
            position,
            density,
            wide,
            marks,
            change = { next ->
                position = next
                if (next.selected != selected) {
                    timelineSelection = null
                    select(next.selected)
                }
            },
            changeDensity = { density = it },
            reschedule = reschedule,
        )
    }
    val timeline: @Composable () -> Unit = {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().testTag("calendar.timeline").orgDragScroll(list, "task"),
            contentPadding = PaddingValues(bottom = UiSpacing.listBottomWithFab),
        ) {
            sections.forEach { section ->
                val day = section.date
                val rows = section.rows
                val journals = section.journals
                stickyHeader(key = "header:$day") {
                    ListSectionHeader(
                        day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                        rows.size.takeIf { it > 0 },
                        Modifier.testTag("calendar.header.$day"),
                    )
                }
                if (day == today && overdue > 0)
                    item(key = "overdue:$day", contentType = "overdue") {
                        TextButton(
                            shape = MaterialTheme.shapes.medium,
                            onClick = showOverdue,
                            modifier = Modifier.padding(horizontal = UiSpacing.sm),
                        ) {
                            Text(tr("overdue") + " · $overdue")
                        }
                    }
                if (rows.isEmpty() && journals.isEmpty())
                    item(key = "empty:$day", contentType = "empty") {
                        Text(
                            tr("no_scheduled_items"),
                            Modifier.padding(horizontal = UiSpacing.edge, vertical = UiSpacing.sectionInset),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                if (section.allDay)
                    item(key = "all-day:$day", contentType = "label") {
                        Text(
                            tr("all_day"),
                            Modifier.padding(horizontal = UiSpacing.edge, vertical = UiSpacing.sm),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                items(rows, key = { "task:$day:${it.id}" }, contentType = { "task" }) { projected ->
                    val original = canonical[projected.id] ?: projected
                    TaskRow(
                        projected,
                        { open(original) },
                        { toggle(original) },
                        draggable = original.isTask && original.kind != "event",
                        allowsSwipe = false,
                        onShowFile = { showFile(original.path, original.start) },
                        dragItem = original,
                        onReschedule = { scheduling = original },
                    )
                }
                items(journals, key = { "journal:$day:${it.id}" }, contentType = { "journal" }) {
                    entry ->
                    JournalCard(entry) { showFile(entry.path, entry.start) }
                }
            }
        }
    }
    scheduling?.let { item ->
        RescheduleSheet(item, { scheduling = null }) { day ->
            scheduling = null
            reschedule(item, day)
        }
    }
    // Keep the dialog outside responsive panel branches so rotation preserves its draft.
    if (picker) {
        ModalPresence()
        val state =
            rememberDatePickerState(
                position.selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            )
        DatePickerDialog(
            onDismissRequest = { picker = false },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            val day =
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            timelineSelection = null
                            position = position.select(day)
                            select(day)
                        }
                        picker = false
                    }
                ) {
                    Text(tr("save"))
                }
            },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.medium, onClick = { picker = false }) { Text(tr("cancel")) } },
        ) {
            DatePicker(state)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (showTopBar)
                CalendarTopAppBar(state, select, windowInsets = WindowInsets(0, 0, 0, 0))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val availableHeight = maxHeight
                val wide = maxWidth >= 600.dp
                val columns = maxWidth >= 720.dp && fontScale < 1.5f
                LaunchedEffect(wide) {
                    if (wide && density == CalendarDensity.WEEK) density = CalendarDensity.MONTH
                }
                LaunchedEffect(fontScale) {
                    if (fontScale >= 1.5f)
                        density = if (wide) CalendarDensity.MONTH else CalendarDensity.WEEK
                }
                if (columns)
                    Row {
                        Box(
                            Modifier.width(380.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            panel(true)
                        }
                        VerticalDivider()
                        Box(Modifier.weight(1f)) { timeline() }
                    }
                else
                    Column {
                        // Short split-screen windows keep the calendar independently scrollable and
                        // the agenda usable.
                        Box(
                            Modifier.heightIn(
                                    max = (availableHeight - 112.dp).coerceAtLeast(120.dp)
                                )
                                .verticalScroll(rememberScrollState())
                        ) {
                            panel(wide)
                        }
                        HorizontalDivider()
                        Box(Modifier.weight(1f)) { timeline() }
                    }
            }
        }
        FloatingActionButton(
            onClick = capture,
            shape = CircleShape,
            elevation = subtleFabElevation(),
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .padding(UiSpacing.edge)
                    .testTag("calendar.capture")
                    .semantics {
                        contentDescription = context.label("new_task")
                        onLongClick(context.label("new_journal")) {
                            journal()
                            true
                        }
                        customActions =
                            listOf(
                                CustomAccessibilityAction(context.label("new_journal")) {
                                    journal()
                                    true
                                }
                            )
                    },
        ) {
            // Keep one accessible button while retaining the journal long press.
            Box(
                Modifier.size(UiSizes.fab)
                    .combinedClickable(onClick = capture, onLongClick = journal)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, null)
            }
        }
    }
}

@Composable
private fun CalendarPanel(
    position: CalendarPosition,
    density: CalendarDensity,
    wide: Boolean,
    marks: Set<LocalDate>,
    change: (CalendarPosition) -> Unit,
    changeDensity: (CalendarDensity) -> Unit,
    reschedule: (OrgHeading, LocalDate) -> Unit,
) {
    val context = LocalContext.current
    val scale = LocalDensity.current
    val largeText = scale.fontScale >= 1.5f
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var dropDate by remember { mutableStateOf<LocalDate?>(null) }
    val minimum = if (wide) CalendarDensity.MONTH else CalendarDensity.WEEK
    val modes = CalendarDensity.entries.filter { it >= minimum }
    val sizing = remember(scale.fontScale) { CalendarSizing(scale.fontScale, largeText) }
    var resizing by remember(sizing, minimum) { mutableStateOf<Float?>(null) }
    var resizeSnapshot by remember { mutableStateOf(position) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val latestPosition by rememberUpdatedState(position)
    val latestDensity by rememberUpdatedState(density)
    val latestChange by rememberUpdatedState(change)
    val latestChangeDensity by rememberUpdatedState(changeDensity)
    fun setDensity(value: CalendarDensity, source: CalendarPosition = latestPosition) {
        val next = value.coerceAtLeast(minimum)
        settling?.cancel()
        if (resizing == null) {
            if (next == latestDensity) return
            resizeSnapshot = source
            resizing = sizing.stop(latestDensity)
        }
        val start = resizing!!
        val end = sizing.stop(next)
        settling = scope.launch {
            // The same position drives content geometry and panel height, during
            // touch tracking and settling. Compose honors the system motion scale.
            if (start != end)
                animate(
                    start,
                    end,
                    animationSpec =
                        spring(dampingRatio = 0.9f, stiffness = 400f, visibilityThreshold = 0.1f),
                ) { value, _ ->
                    resizing = value.coerceIn(sizing.stop(minimum), sizing.yearStop)
                }
            if (next != latestDensity)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (next == CalendarDensity.WEEK)
                latestChange(latestPosition.copy(visible = YearMonth.from(latestPosition.selected)))
            latestChangeDensity(next)
            resizing = null
        }
    }
    DisposableEffect(sizing, minimum) { onDispose { settling?.cancel() } }
    val anchor = remember(density) { position }
    val pager = key(density) { rememberPagerState(initialPage = 10000, pageCount = { 20001 }) }
    var navigating by remember(density) { mutableStateOf(false) }
    fun offset(value: CalendarPosition): Int =
        when (density) {
            CalendarDensity.WEEK ->
                ChronoUnit.WEEKS.between(startOfWeek(anchor.selected), startOfWeek(value.selected))
                    .toInt()
            CalendarDensity.MONTH ->
                ChronoUnit.MONTHS.between(anchor.visible, value.visible).toInt()
            CalendarDensity.YEAR -> value.visible.year - anchor.visible.year
        }
    LaunchedEffect(density, position.selected, position.visible) {
        val page = 10000 + offset(position)
        if (
            !pager.isScrollInProgress &&
                page != pager.currentPage &&
                page in 0 until pager.pageCount
        ) {
            navigating = true
            try {
                pager.animateScrollToPage(page, animationSpec = tween(300))
            } finally {
                navigating = false
            }
        }
    }
    LaunchedEffect(pager, density) {
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (page, moving) ->
                if (!moving && !navigating) {
                    val delta = page - 10000 - offset(latestPosition)
                    if (delta != 0) latestChange(latestPosition.page(density, delta.toLong()))
                }
            }
    }
    val viewportHeight =
        (sizing.height(density) - if (density == CalendarDensity.YEAR) 0f else sizing.weekdayHeight)
            .dp
    val displayedDensity = resizing?.let(sizing::nearest) ?: density
    Column(Modifier.fillMaxWidth().padding(horizontal = UiSpacing.edge)) {
        if (resizing != null)
            CalendarResizePreview(resizeSnapshot, resizing!!, marks, sizing) { week ->
                val dates =
                    if (week) (0L..6L).map { startOfWeek(resizeSnapshot.selected).plusDays(it) }
                    else
                        (1..resizeSnapshot.visible.lengthOfMonth()).map {
                            resizeSnapshot.visible.atDay(it)
                        }
                AccessibleCalendarDays(
                    dates,
                    resizeSnapshot.visible,
                    resizeSnapshot.selected,
                    marks,
                    {},
                    { _, _ -> },
                ) {}
            }
        else
            Column {
                if (density != CalendarDensity.YEAR && !largeText)
                    WeekdayHeader(sizing.weekdayHeight)
                VerticalPager(
                    state = pager,
                    modifier =
                        Modifier.fillMaxWidth().height(viewportHeight).testTag("calendar.pager"),
                    flingBehavior =
                        PagerDefaults.flingBehavior(
                            pager,
                            pagerSnapDistance = PagerSnapDistance.atMost(1),
                        ),
                ) { page ->
                    val shown = anchor.page(density, (page - 10000).toLong())
                    if (density == CalendarDensity.YEAR)
                        YearGrid(shown.visible.year, position.selected, sizing) { month ->
                            val next = position.month(month)
                            change(next)
                            setDensity(CalendarDensity.MONTH, next)
                        }
                    else {
                        val dates =
                            if (density == CalendarDensity.WEEK)
                                (0L..6L).map { startOfWeek(shown.selected).plusDays(it) }
                            else if (largeText)
                                (1..shown.visible.lengthOfMonth()).map { shown.visible.atDay(it) }
                            else calendarMonthDates(shown.visible)
                        val choose: (LocalDate) -> Unit = { day ->
                            if (day != position.selected)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            change(position.select(day))
                        }
                        if (largeText)
                            AccessibleCalendarDays(
                                dates,
                                shown.visible,
                                position.selected,
                                marks,
                                choose,
                                reschedule,
                            ) {
                                dropDate = it
                            }
                        else
                            Column {
                                dates.chunked(7).forEach { week ->
                                    Row {
                                        week.forEach { day ->
                                            DayCell(
                                                day,
                                                shown.visible,
                                                position.selected,
                                                day in marks,
                                                false,
                                                Modifier.weight(1f).height(sizing.dayHeight.dp),
                                                choose,
                                                reschedule,
                                            ) {
                                                dropDate = it
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        var handleRootY by remember { mutableFloatStateOf(0f) }
        val currentSetDensity by rememberUpdatedState<(CalendarDensity) -> Unit>({ setDensity(it) })
        Box(
            Modifier.fillMaxWidth()
                .height(24.dp)
                .testTag("calendar.densityHandle")
                .onGloballyPositioned { handleRootY = it.positionInRoot().y }
                .pointerInput(sizing, minimum) {
                    var origin = 0f
                    var touchRootY = 0f
                    detectVerticalDragGestures(
                        onDragStart = { touch ->
                            settling?.cancel()
                            origin = resizing ?: sizing.stop(latestDensity)
                            if (resizing == null) resizeSnapshot = latestPosition
                            resizing = origin
                            touchRootY = handleRootY + touch.y
                        },
                        onDragCancel = {
                            currentSetDensity(sizing.nearest(resizing ?: origin))
                        },
                        onDragEnd = {
                            currentSetDensity(sizing.nearest(resizing ?: origin))
                        },
                    ) { change, _ ->
                        change.consume()
                        // Local deltas include the handle's own movement as the panel
                        // expands. Root coordinates keep the calendar attached to touch.
                        resizing =
                            (origin +
                                    (handleRootY + change.position.y - touchRootY) / scale.density)
                                .coerceIn(sizing.stop(minimum), sizing.yearStop)
                    }
                }
                .semantics {
                    contentDescription = context.label("calendar_view")
                    stateDescription = context.label(displayedDensity.name.lowercase())
                    customActions =
                        modes
                            .filter { resizing != null || it != displayedDensity }
                            .map { target ->
                                CustomAccessibilityAction(context.label(target.name.lowercase())) {
                                    setDensity(target)
                                    true
                                }
                            }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(40.dp, 4.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (resizing == null) MaterialTheme.colorScheme.outlineVariant
                        else MaterialTheme.colorScheme.primary
                    )
            )
        }
        dropDate?.let {
            Text(
                tr("scheduled") + ": $it",
                Modifier.padding(bottom = UiSpacing.sm),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

internal fun startOfWeek(day: LocalDate): LocalDate =
    day.with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.getDefault()).firstDayOfWeek))

@Composable
internal fun WeekdayHeader(height: Float) {
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    Row(Modifier.fillMaxWidth().height(height.dp), verticalAlignment = Alignment.CenterVertically) {
        for (index in 0L..6L) {
            val day = first.plus(index)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (day in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
                            MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    month: YearMonth,
    selected: LocalDate,
    marked: Boolean,
    largeText: Boolean,
    modifier: Modifier,
    choose: (LocalDate) -> Unit,
    reschedule: (OrgHeading, LocalDate) -> Unit,
    targeting: (LocalDate?) -> Unit,
) {
    var targeted by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val today = day == LocalToday.current.date
    Surface(
        onClick = { choose(day) },
        color = Color.Transparent,
        shape = MaterialTheme.shapes.large,
        modifier =
            modifier
                .testTag("calendar.day.$day")
                .semantics {
                    contentDescription =
                        day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
                    this.selected = day == selected
                    stateDescription =
                        listOfNotNull(
                                context.label("today").takeIf { today },
                                context.label("has_items").takeIf { marked },
                            )
                            .joinToString(", ")
                }
                .orgDrop(
                    "task",
                    targeted = {
                        targeted = it
                        targeting(if (it) day else null)
                    },
                ) { payload ->
                    (payload as? OrgHeading)?.let { reschedule(it, day) }
                },
    ) {
        if (largeText)
            Surface(
                color =
                    when {
                        targeted -> MaterialTheme.colorScheme.tertiaryContainer
                        day == selected -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surface
                    },
                shape = MaterialTheme.shapes.large,
                border =
                    if (today || targeted)
                        BorderStroke(
                            if (targeted) UiSizes.activeBorder else UiSizes.border,
                            MaterialTheme.colorScheme.primary,
                        )
                    else null,
                modifier = Modifier.padding(UiSpacing.xxs),
            ) {
                Column(
                    Modifier.padding(vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        day.dayOfMonth.toString(),
                        color =
                            when {
                                day == selected -> MaterialTheme.colorScheme.onPrimaryContainer
                                YearMonth.from(day) != month -> MaterialTheme.colorScheme.outline
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                    )
                    Box(
                        Modifier.size(4.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (marked) MaterialTheme.colorScheme.primary else Color.Transparent
                            )
                    )
                }
            }
        else CalendarDayContent(day, month, selected, marked, 0f, Modifier.fillMaxSize(), targeted)
    }
}

@Composable
private fun AccessibleCalendarDays(
    dates: List<LocalDate>,
    month: YearMonth,
    selected: LocalDate,
    marks: Set<LocalDate>,
    choose: (LocalDate) -> Unit,
    reschedule: (OrgHeading, LocalDate) -> Unit,
    targeting: (LocalDate?) -> Unit,
) {
    val scale = LocalDensity.current.fontScale
    val strip = rememberLazyListState()
    LaunchedEffect(selected, dates) {
        dates.indexOf(selected).takeIf { it >= 0 }?.let { strip.scrollToItem(it) }
    }
    LazyRow(
        state = strip,
        modifier = Modifier.fillMaxWidth().height((76 * scale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(dates, key = { it.toString() }) { day ->
            DayCell(
                day,
                month,
                selected,
                day in marks,
                true,
                Modifier.width((60 * scale).dp),
                choose,
                reschedule,
                targeting,
            )
        }
    }
}

internal fun calendarMonthDates(month: YearMonth) =
    (0L..41L).map { startOfWeek(month.atDay(1)).plusDays(it) }

@Composable
internal fun CalendarMonthContent(
    dates: List<LocalDate>,
    month: YearMonth,
    selected: LocalDate,
    marks: Set<LocalDate>,
    progress: Float,
    modifier: Modifier,
) {
    Column(modifier) {
        dates.chunked(7).forEach { week ->
            Row(Modifier.weight(1f)) {
                week.forEach { day ->
                    CalendarDayContent(
                        day,
                        month,
                        selected,
                        day in marks,
                        progress,
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDayContent(
    day: LocalDate,
    month: YearMonth,
    selected: LocalDate,
    marked: Boolean,
    progress: Float,
    modifier: Modifier,
    targeted: Boolean = false,
) {
    val inMonth = YearMonth.from(day) == month
    val today = day == LocalToday.current.date
    val colors = MaterialTheme.colorScheme
    Surface(
        color =
            when {
                targeted -> colors.tertiaryContainer
                day == selected -> colors.primaryContainer
                else -> Color.Transparent
            },
        shape = MaterialTheme.shapes.medium,
        border =
            if (today || targeted)
                BorderStroke(
                    if (targeted) UiSizes.activeBorder else UiSizes.border,
                    colors.primary.copy(alpha = 1 - progress),
                )
            else null,
        modifier =
            modifier.padding(lerp(2f, 0f, progress).dp).graphicsLayer {
                alpha = if (inMonth) 1f else 1 - progress
            },
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                fontSize = lerp(16f, 8f, progress).sp,
                lineHeight = lerp(24f, 11f, progress).sp,
                color =
                    when {
                        day == selected -> colors.onPrimaryContainer
                        !inMonth -> colors.outline
                        else -> colors.onSurface
                    },
                maxLines = 1,
            )
            Box(
                Modifier.size((4 * (1 - progress)).dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (marked) colors.primary else Color.Transparent)
            )
        }
    }
}

@Composable
internal fun YearGrid(
    year: Int,
    selected: LocalDate,
    sizing: CalendarSizing,
    hiddenMonth: YearMonth? = null,
    modifier: Modifier = Modifier,
    choose: (YearMonth) -> Unit,
) {
    Column(modifier.fillMaxWidth().height(sizing.yearHeight.dp)) {
        for (row in 0..3) Row(Modifier.height(sizing.yearRowHeight.dp)) {
            for (col in 1..3) {
                val month = YearMonth.of(year, row * 3 + col)
                Surface(
                    onClick = { choose(month) },
                    modifier =
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .testTag("calendar.month.$month")
                            .semantics {
                                contentDescription =
                                    month.format(DateTimeFormatter.ofPattern("LLLL yyyy"))
                                this.selected = month == YearMonth.from(selected)
                            },
                    color =
                        if (sizing.largeText && month == YearMonth.from(selected))
                            MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Text(
                            month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            style = MaterialTheme.typography.titleSmall,
                            modifier =
                                if (sizing.largeText) Modifier.align(Alignment.Center)
                                else
                                    Modifier.align(Alignment.TopCenter)
                                        .offset(
                                            y =
                                                (sizing.miniGridTop - sizing.miniLabelHeight - 6f)
                                                    .dp
                                        )
                                        .height(sizing.miniLabelHeight.dp),
                        )
                        if (!sizing.largeText && month != hiddenMonth) {
                            CalendarMonthContent(
                                remember(month) { calendarMonthDates(month) },
                                month,
                                selected,
                                emptySet(),
                                1f,
                                Modifier.padding(horizontal = 7.dp)
                                    .offset(y = sizing.miniGridTop.dp)
                                    .fillMaxWidth()
                                    .height(78.dp)
                                    .clearAndSetSemantics {},
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JournalCard(entry: JournalEntry, open: () -> Unit) {
    OutlinedCard(
        onClick = open,
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiSpacing.edge, vertical = UiSpacing.sm),
    ) {
        Column(Modifier.padding(UiSpacing.edge), verticalArrangement = Arrangement.spacedBy(UiSpacing.sm)) {
            Text(
                entry.date.toLocalTime().toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.text.selection.SelectionContainer { Text(entry.body) }
            Text(entry.path, style = MaterialTheme.typography.labelSmall)
        }
    }
}
