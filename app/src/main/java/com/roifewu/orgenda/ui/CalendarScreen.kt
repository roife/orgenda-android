@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.roifewu.orgenda.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.WorkspaceSnapshot
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class CalendarDay(val date: LocalDate, val rows: List<OrgHeading>, val journals: List<JournalEntry>, val allDay: Boolean, val headerIndex: Int)

fun occurs(item: OrgHeading, day: LocalDate) = Agenda.project(item, day) != null

@Composable fun CalendarScreen(snapshot: WorkspaceSnapshot, selected: LocalDate, select: (LocalDate) -> Unit,
    open: (OrgHeading) -> Unit, toggle: (OrgHeading) -> Unit, journal: () -> Unit,
    showFile: (String, Int) -> Unit, capture: () -> Unit, showOverdue: () -> Unit,
    reschedule: (OrgHeading, LocalDate) -> Unit) {
    val currentDay = LocalToday.current
    val today = currentDay.date
    val fontScale = LocalDensity.current.fontScale
    var density by rememberSaveable { mutableStateOf(CalendarDensity.WEEK) }
    var position by rememberSaveable(stateSaver = listSaver<CalendarPosition, String>(
        save = { listOf(it.selected.toString(), it.visible.toString(), it.preferredDay.toString()) },
        restore = { CalendarPosition(LocalDate.parse(it[0]), YearMonth.parse(it[1]), it[2].toInt()) }
    )) { mutableStateOf(CalendarPosition(selected)) }
    val latestSnapshot by rememberUpdatedState(snapshot)
    val schedulingSaver = remember {
        Saver<OrgHeading?, List<String>>(
            save = { item -> item?.let { listOf(latestSnapshot.workspace?.id.orEmpty(), it.path, it.start.toString(), it.sourceHash) } },
            restore = { saved ->
                latestSnapshot.takeIf { it.workspace?.id.orEmpty() == saved[0] }?.documents?.get(saved[1])?.headings
                    ?.firstOrNull { it.start.toString() == saved[2] && it.sourceHash == saved[3] }
            }
        )
    }
    var scheduling by rememberSaveable(stateSaver = schedulingSaver) { mutableStateOf<OrgHeading?>(null) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var timelineAnchor by rememberSaveable { mutableStateOf(selected.toString()) }
    var timelineSelection by rememberSaveable { mutableStateOf<String?>(null) }
    var todayRequest by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var userScrolling by remember { mutableStateOf(false) }
    val days = remember(timelineAnchor) { (-30L..365L).map { LocalDate.parse(timelineAnchor).plusDays(it) } }
    val marks = remember(snapshot.revision) { snapshot.items.filter { !it.done }.flatMap { listOfNotNull(it.scheduled, it.deadline, it.event).map { stamp -> stamp.date.toLocalDate() } }.toSet() + snapshot.journals.map { it.date.toLocalDate() } }
    val overdue = remember(snapshot.revision, today) { snapshot.items.count { Agenda.overdue(it, today) } }
    val canonical = remember(snapshot.revision) { snapshot.items.associateBy { it.id } }
    val sections by produceState<List<CalendarDay>>(emptyList(), snapshot.revision, days, currentDay, overdue) {
        value = withContext(Dispatchers.Default) {
            val entries = snapshot.journals.groupBy { it.date.toLocalDate() }
            var index = 0
            days.map { day ->
                val rows = Agenda.on(snapshot.items, day)
                val (allDay, timed) = rows.partition { it.kind == "event" && it.agendaStamp?.hasTime == false }
                val journals = entries[day].orEmpty().sortedWith(compareByDescending<JournalEntry> { it.date }.thenBy { it.path }.thenByDescending { it.start })
                CalendarDay(day, allDay + timed, journals, allDay.isNotEmpty(), index).also {
                    index += 1 + rows.size + journals.size + (if (allDay.isNotEmpty()) 1 else 0) +
                        (if (day == today && overdue > 0) 1 else 0) + (if (rows.isEmpty() && journals.isEmpty()) 1 else 0)
                }
            }
        }
    }
    val currentSelect by rememberUpdatedState(select)
    LaunchedEffect(selected) { if (selected != position.selected) position = position.select(selected) }
    LaunchedEffect(dragged, list.isScrollInProgress) { if (dragged) userScrolling = true; if (!list.isScrollInProgress) userScrolling = false }
    LaunchedEffect(list, sections) {
        snapshotFlow { list.firstVisibleItemIndex to userScrolling }.distinctUntilChanged().collect { (index, scrolling) ->
            if (scrolling) sections.lastOrNull { it.headerIndex <= index }?.date?.let { day -> timelineSelection = day.toString(); currentSelect(day) }
        }
    }
    LaunchedEffect(selected, todayRequest, days, sections, fontScale) {
        if (selected.toString() == timelineSelection && todayRequest == 0) return@LaunchedEffect
        timelineSelection = null
        if (selected !in days.first()..days.last()) { timelineAnchor = selected.toString(); return@LaunchedEffect }
        userScrolling = false
        val index = sections.firstOrNull { it.date == selected }?.headerIndex ?: return@LaunchedEffect
        list.scrollToItem(index)
        todayRequest = 0
    }
    val panel: @Composable (Boolean) -> Unit = { wide -> CalendarPanel(position, density, wide, marks,
        change = { next -> position = next; if (next.selected != selected) { timelineSelection = null; select(next.selected) } },
        changeDensity = { density = it }, today = { timelineSelection = null; todayRequest++; position = position.select(today); select(today) },
        capture = capture, journal = journal, reschedule = reschedule, openDatePicker = { picker = true }) }
    val timeline: @Composable () -> Unit = {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("calendar.timeline"), contentPadding = PaddingValues(bottom = 96.dp)) {
            sections.forEach { section ->
                val day = section.date
                val rows = section.rows
                val journals = section.journals
                stickyHeader(key = "header:$day") {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.testTag("calendar.header.$day")) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
                            if (rows.isNotEmpty()) Text(rows.size.toString(), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (day == today && overdue > 0) item(key = "overdue:$day", contentType = "overdue") {
                    TextButton(onClick = showOverdue, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("overdue") + " · $overdue") }
                }
                if (rows.isEmpty() && journals.isEmpty()) item(key = "empty:$day", contentType = "empty") {
                    Text(tr("no_scheduled_items"), Modifier.padding(horizontal = 16.dp, vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (section.allDay) item(key = "all-day:$day", contentType = "label") {
                    Text(tr("all_day"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                }
                items(rows, key = { "task:$day:${it.id}" }, contentType = { "task" }) { projected ->
                    val original = canonical[projected.id] ?: projected
                    TaskRow(projected, { open(original) }, { toggle(original) }, draggable = original.isTask && original.kind != "event", allowsSwipe = false,
                        onShowFile = { showFile(original.path, original.start) }, dragItem = original, onReschedule = { scheduling = original })
                }
                items(journals, key = { "journal:$day:${it.id}" }, contentType = { "journal" }) { entry -> JournalCard(entry) { showFile(entry.path, entry.start) } }
            }
        }
    }
    scheduling?.let { item -> RescheduleSheet(item, { scheduling = null }) { day -> scheduling = null; reschedule(item, day) } }
    // Keep the dialog outside responsive panel branches so rotation preserves its draft.
    if (picker) {
        ModalPresence()
        val state = rememberDatePickerState(position.selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { picker = false }, confirmButton = { TextButton(onClick = {
            state.selectedDateMillis?.let { millis ->
                val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                timelineSelection = null
                position = position.select(day)
                select(day)
            }
            picker = false
        }) { Text(tr("save")) } }, dismissButton = { TextButton(onClick = { picker = false }) { Text(tr("cancel")) } }) { DatePicker(state) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableHeight = maxHeight
        val wide = maxWidth >= 600.dp
        val columns = maxWidth >= 720.dp && fontScale < 1.5f
        LaunchedEffect(wide) { if (wide && density == CalendarDensity.WEEK) density = CalendarDensity.MONTH }
        LaunchedEffect(fontScale) { if (fontScale >= 1.5f) density = if (wide) CalendarDensity.MONTH else CalendarDensity.WEEK }
        if (columns) Row { Box(Modifier.width(380.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { panel(true) }; VerticalDivider(); Box(Modifier.weight(1f)) { timeline() } }
        else Column {
            // Short split-screen windows keep the calendar independently scrollable and the agenda usable.
            Box(Modifier.heightIn(max = (availableHeight - 112.dp).coerceAtLeast(120.dp)).verticalScroll(rememberScrollState())) { panel(wide) }
            HorizontalDivider(); Box(Modifier.weight(1f)) { timeline() }
        }
    }
}

@Composable private fun CalendarPanel(position: CalendarPosition, density: CalendarDensity, wide: Boolean, marks: Set<LocalDate>,
    change: (CalendarPosition) -> Unit, changeDensity: (CalendarDensity) -> Unit, today: () -> Unit,
    capture: () -> Unit, journal: () -> Unit, reschedule: (OrgHeading, LocalDate) -> Unit, openDatePicker: () -> Unit) {
    val context = LocalContext.current
    val todayDate = LocalToday.current.date
    val scale = LocalDensity.current
    val largeText = scale.fontScale >= 1.5f
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var dropDate by remember { mutableStateOf<LocalDate?>(null) }
    val minimum = if (wide) CalendarDensity.MONTH else CalendarDensity.WEEK
    val modes = CalendarDensity.entries.filter { it >= minimum }
    fun setDensity(value: CalendarDensity) {
        val next = value.coerceAtLeast(minimum)
        if (next != density) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (next == CalendarDensity.WEEK) change(position.copy(visible = YearMonth.from(position.selected)))
            changeDensity(next)
        }
    }
    val anchor = remember(density) { position }
    val pager = key(density) { rememberPagerState(initialPage = 10000, pageCount = { 20001 }) }
    val latestPosition by rememberUpdatedState(position)
    val latestChange by rememberUpdatedState(change)
    var navigating by remember(density) { mutableStateOf(false) }
    fun offset(value: CalendarPosition): Int = when (density) {
        CalendarDensity.WEEK -> ChronoUnit.WEEKS.between(startOfWeek(anchor.selected), startOfWeek(value.selected)).toInt()
        CalendarDensity.MONTH -> ChronoUnit.MONTHS.between(anchor.visible, value.visible).toInt()
        CalendarDensity.YEAR -> value.visible.year - anchor.visible.year
    }
    LaunchedEffect(density, position.selected, position.visible) {
        val page = 10000 + offset(position)
        if (!pager.isScrollInProgress && page != pager.currentPage && page in 0 until pager.pageCount) {
            navigating = true; pager.scrollToPage(page); navigating = false
        }
    }
    LaunchedEffect(pager, density) {
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }.distinctUntilChanged().collect { (page, moving) ->
            if (!moving && !navigating) {
                val delta = page - 10000 - offset(latestPosition)
                if (delta != 0) latestChange(latestPosition.page(density, delta.toLong()))
            }
        }
    }
    var resizing by remember { mutableStateOf<Float?>(null) }
    val viewportHeight = when (density) { CalendarDensity.WEEK -> if (largeText) (76 * scale.fontScale).dp else 56.dp; CalendarDensity.MONTH -> if (largeText) (76 * scale.fontScale).dp else (6 * 52).dp; CalendarDensity.YEAR -> if (largeText) (4 * 60 * scale.fontScale).dp else (4 * 118).dp }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = openDatePicker, modifier = Modifier.weight(1f).testTag("calendar.datePicker")) { Text(if (density == CalendarDensity.YEAR) position.visible.year.toString() else position.visible.format(DateTimeFormatter.ofPattern("LLLL yyyy")), style = MaterialTheme.typography.titleLarge) }
            if (position.selected != todayDate || position.visible != YearMonth.from(todayDate)) IconButton(onClick = today) { Icon(Icons.Default.Today, tr("today")) }
            Box(Modifier.size(48.dp).combinedClickable(onClick = capture, onLongClick = journal).semantics {
                contentDescription = context.label("new_task")
                customActions = listOf(CustomAccessibilityAction(context.label("new_journal")) { journal(); true })
            }, contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("previous")) }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) { modes.forEach { mode -> FilterChip(mode == density, { setDensity(mode) }, label = { Text(tr(mode.name.lowercase())) }, modifier = Modifier.padding(horizontal = 2.dp)) } }
            IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(pager.pageCount - 1)) } }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("next")) }
        }
        if (density != CalendarDensity.YEAR && !largeText) WeekdayHeader()
        if (resizing != null && !largeText) CalendarResizePreview(position, resizing!!, marks)
        else VerticalPager(state = pager, modifier = Modifier.fillMaxWidth().height(viewportHeight).testTag("calendar.pager"),
            flingBehavior = PagerDefaults.flingBehavior(pager, pagerSnapDistance = PagerSnapDistance.atMost(1))) { page ->
            val shown = anchor.page(density, (page - 10000).toLong())
            if (density == CalendarDensity.YEAR) YearGrid(shown.visible.year, position.selected, largeText) { month -> change(position.month(month)); setDensity(CalendarDensity.MONTH) }
            else {
                val dates = if (density == CalendarDensity.WEEK) (0L..6L).map { startOfWeek(shown.selected).plusDays(it) }
                    else if (largeText) (1..shown.visible.lengthOfMonth()).map { shown.visible.atDay(it) }
                    else (0L..41L).map { startOfWeek(shown.visible.atDay(1)).plusDays(it) }
                val choose: (LocalDate) -> Unit = { day -> if (day != position.selected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); change(position.select(day)) }
                if (largeText) {
                    val strip = rememberLazyListState()
                    LaunchedEffect(position.selected, dates) { dates.indexOf(position.selected).takeIf { it >= 0 }?.let { strip.scrollToItem(it) } }
                    LazyRow(state = strip, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        items(dates, key = { it.toString() }) { day -> DayCell(day, shown.visible, position.selected, day in marks, true, Modifier.width((60 * scale.fontScale).dp), choose, reschedule) { dropDate = it } }
                    }
                } else Column { dates.chunked(7).forEach { week -> Row { week.forEach { day -> DayCell(day, shown.visible, position.selected, day in marks, false, Modifier.weight(1f).height(if (density == CalendarDensity.WEEK) 56.dp else 52.dp), choose, reschedule) { dropDate = it } } } } }
            }
        }
        var drag by remember { mutableFloatStateOf(0f) }
        Box(Modifier.fillMaxWidth().height(48.dp).testTag("calendar.densityHandle").pointerInput(density, wide) {
            detectVerticalDragGestures(onDragStart = { drag = 0f; resizing = viewportHeight.value }, onDragCancel = { drag = 0f; resizing = null }, onDragEnd = {
                if (kotlin.math.abs(drag) > 24 * scale.density) {
                    val next = if (!largeText && resizing!! >= 440f) CalendarDensity.YEAR else CalendarDensity.entries[(density.ordinal + if (drag > 0) 1 else -1).coerceIn(minimum.ordinal, 2)]
                    setDensity(next)
                }
                drag = 0f; resizing = null
            }) { change, amount -> change.consume(); drag += amount; resizing = (viewportHeight.value + drag / scale.density).coerceIn(if (wide) 312f else 56f, 472f) }
        }.semantics {
            contentDescription = context.label("calendar_view"); stateDescription = context.label(density.name.lowercase())
            customActions = modes.filter { it != density }.map { target -> CustomAccessibilityAction(context.label(target.name.lowercase())) { setDensity(target); true } }
        }, contentAlignment = Alignment.Center) { Box(Modifier.size(40.dp, 4.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.outlineVariant)) }
        dropDate?.let { Text(tr("scheduled") + ": $it", Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable private fun CalendarResizePreview(position: CalendarPosition, height: Float, marks: Set<LocalDate>) {
    val density = LocalDensity.current.density
    val month = YearMonth.from(position.selected)
    val dates = remember(month) { (0L..41L).map { startOfWeek(month.atDay(1)).plusDays(it) } }
    val week = dates.indexOf(position.selected) / 7
    val expansion = ((height - 56f) / 256f).coerceIn(0f, 1f)
    val year = ((height - 312f) / 160f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(height.dp).clipToBounds().semantics { hideFromAccessibility() }) {
        if (year > 0f) Box(Modifier.fillMaxWidth().height(472.dp).graphicsLayer { alpha = year }) { YearGrid(position.visible.year, position.selected, false) {} }
        Column(Modifier.fillMaxWidth().height(312.dp).graphicsLayer {
            translationY = -week * 52f * density * (1f - expansion)
            alpha = 1f - year; scaleX = 1f - year * 0.4f; scaleY = scaleX
        }) { dates.chunked(7).forEach { row -> Row { row.forEach { day ->
            DayCell(day, month, position.selected, day in marks, false, Modifier.weight(1f).height(52.dp), {}, { _, _ -> }) {}
        } } } }
    }
}

private fun startOfWeek(day: LocalDate): LocalDate = day.minusDays(((day.dayOfWeek.value - WeekFields.of(Locale.getDefault()).firstDayOfWeek.value + 7) % 7).toLong())

@Composable private fun WeekdayHeader() {
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { for (index in 0L..6L) {
        val day = first.plus(index)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelMedium, color = if (day in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant) }
    } }
}

@Composable private fun DayCell(day: LocalDate, month: YearMonth, selected: LocalDate, marked: Boolean, largeText: Boolean, modifier: Modifier,
    choose: (LocalDate) -> Unit, reschedule: (OrgHeading, LocalDate) -> Unit, targeting: (LocalDate?) -> Unit) {
    var targeted by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val today = day == LocalToday.current.date
    Surface(onClick = { choose(day) }, color = when { targeted -> MaterialTheme.colorScheme.tertiaryContainer; day == selected -> MaterialTheme.colorScheme.primaryContainer; else -> MaterialTheme.colorScheme.surface },
        shape = MaterialTheme.shapes.large,
        border = if (today || targeted) BorderStroke(if (targeted) 2.dp else 1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier.padding(2.dp).testTag("calendar.day.$day").semantics {
            contentDescription = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
            this.selected = day == selected
            stateDescription = listOfNotNull(context.label("today").takeIf { today }, context.label("has_items").takeIf { marked }).joinToString(", ")
        }.orgDrop("task", targeted = { targeted = it; targeting(if (it) day else null) }) { payload -> (payload as? OrgHeading)?.let { reschedule(it, day) } }) {
        Column(Modifier.padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (largeText) Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = MaterialTheme.typography.labelMedium)
            Text(day.dayOfMonth.toString(), color = when { day == selected -> MaterialTheme.colorScheme.onPrimaryContainer; YearMonth.from(day) != month -> MaterialTheme.colorScheme.outline; else -> MaterialTheme.colorScheme.onSurface })
            Box(Modifier.size(4.dp).clip(MaterialTheme.shapes.small).background(if (marked) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent))
        }
    }
}

@Composable private fun YearGrid(year: Int, selected: LocalDate, largeText: Boolean, choose: (YearMonth) -> Unit) {
    Column(Modifier.fillMaxSize()) { for (row in 0..3) Row(Modifier.weight(1f)) { for (col in 1..3) {
        val month = YearMonth.of(year, row * 3 + col)
        Surface(onClick = { choose(month) }, modifier = Modifier.weight(1f).fillMaxHeight().padding(3.dp).testTag("calendar.month.$month").semantics { contentDescription = month.format(DateTimeFormatter.ofPattern("LLLL yyyy")); this.selected = month == YearMonth.from(selected) },
            color = if (largeText && month == YearMonth.from(selected)) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = MaterialTheme.typography.titleSmall)
                if (!largeText) {
                    val dates = (0L..41L).map { startOfWeek(month.atDay(1)).plusDays(it) }
                    dates.chunked(7).forEach { days -> Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) { days.forEach { day ->
                        Box(Modifier.weight(1f).height(13.dp).background(if (day == selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) { Text(if (YearMonth.from(day) == month) day.dayOfMonth.toString() else "", fontSize = 8.sp, lineHeight = 11.sp) }
                    } } }
                }
            }
        }
    } } }
}

@Composable fun JournalCard(entry: JournalEntry, open: () -> Unit) {
    OutlinedCard(onClick = open, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.date.toLocalTime().toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.text.selection.SelectionContainer { Text(entry.body) }
            Text(entry.path, style = MaterialTheme.typography.labelSmall)
        }
    }
}
