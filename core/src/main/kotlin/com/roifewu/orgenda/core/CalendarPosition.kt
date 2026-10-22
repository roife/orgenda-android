package com.roifewu.orgenda.core

import java.time.LocalDate
import java.time.YearMonth

enum class CalendarDensity { WEEK, MONTH, YEAR }
data class CalendarPosition(val selected: LocalDate, val visible: YearMonth = YearMonth.from(selected), val preferredDay: Int = selected.dayOfMonth) {
    fun select(day: LocalDate) = copy(selected = day, visible = YearMonth.from(day), preferredDay = day.dayOfMonth)
    fun page(density: CalendarDensity, delta: Long): CalendarPosition = when (density) {
        CalendarDensity.WEEK -> select(selected.plusWeeks(delta))
        CalendarDensity.MONTH -> visible.plusMonths(delta).let { copy(selected = it.atDay(preferredDay.coerceAtMost(it.lengthOfMonth())), visible = it) }
        CalendarDensity.YEAR -> copy(visible = visible.plusYears(delta))
    }
    fun month(month: YearMonth) = select(month.atDay(1))
}
