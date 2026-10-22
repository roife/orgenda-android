@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roifewu.orgenda.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.core.CalendarDensity
import com.roifewu.orgenda.core.CalendarPosition
import java.time.LocalDate
import java.time.YearMonth

@Stable
class CalendarScreenState(
    initialPosition: CalendarPosition,
    initialDensity: CalendarDensity = CalendarDensity.WEEK,
    initialPicker: Boolean = false,
) {
    internal val position = mutableStateOf(initialPosition)
    internal val density = mutableStateOf(initialDensity)
    internal val picker = mutableStateOf(initialPicker)
    internal val todayRequest = mutableIntStateOf(0)
}

@Composable
fun rememberCalendarScreenState(selected: LocalDate, navigation: Int = 0): CalendarScreenState =
    rememberSaveable(
        navigation,
        saver = listSaver(
            save = {
                listOf(
                    it.position.value.selected.toString(),
                    it.position.value.visible.toString(),
                    it.position.value.preferredDay.toString(),
                    it.density.value.name,
                    it.picker.value.toString(),
                )
            },
            restore = {
                CalendarScreenState(
                    CalendarPosition(LocalDate.parse(it[0]), YearMonth.parse(it[1]), it[2].toInt()),
                    CalendarDensity.valueOf(it[3]),
                    it[4].toBoolean(),
                )
            },
        ),
    ) {
        CalendarScreenState(CalendarPosition(selected))
    }

@Composable
internal fun CalendarTopAppBar(
    state: CalendarScreenState,
    select: (LocalDate) -> Unit,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val today = LocalToday.current.date
    val position = state.position.value
    TopAppBar(
        modifier = Modifier.testTag("calendar.topBar"),
        title = {
            TextButton(
                onClick = { state.picker.value = true },
                shape = RectangleShape,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.testTag("calendar.datePicker").semantics { heading() },
            ) {
                CalendarDateHeading(
                    position.selected,
                    position.visible,
                    state.density.value == CalendarDensity.YEAR,
                )
            }
        },
        actions = {
            if (position.selected != today || position.visible != YearMonth.from(today)) {
                IconButton(
                    onClick = {
                        state.todayRequest.intValue++
                        state.position.value = position.select(today)
                        select(today)
                    },
                    modifier = Modifier.testTag("calendar.today"),
                ) {
                    Icon(Icons.Default.Today, tr("return_today"))
                }
            }
        },
        windowInsets = windowInsets,
    )
}
