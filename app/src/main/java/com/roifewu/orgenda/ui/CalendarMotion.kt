package com.roifewu.orgenda.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.roifewu.orgenda.core.CalendarDensity
import com.roifewu.orgenda.core.CalendarPosition
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow

// Drag stops are separate from heights: accessibility week/month strips have
// equal heights, but both still need a reachable stop (as in OrgendaCalendarSizing).
internal data class CalendarSizing(val fontScale: Float, val largeText: Boolean) {
    val weekdayHeight = if (largeText) 0f else 24f * fontScale
    val dayHeight = 52f
    val monthHeight = dayHeight * 6
    val yearRowHeight = if (largeText) 60f * fontScale else 118f
    val yearHeight = yearRowHeight * 4
    val monthStop = maxOf(96f, height(CalendarDensity.MONTH) - height(CalendarDensity.WEEK))
    val yearStop =
        monthStop + maxOf(96f, height(CalendarDensity.YEAR) - height(CalendarDensity.MONTH))

    fun height(density: CalendarDensity): Float =
        when (density) {
            CalendarDensity.WEEK -> if (largeText) 76f * fontScale else dayHeight + weekdayHeight
            CalendarDensity.MONTH -> if (largeText) 76f * fontScale else monthHeight + weekdayHeight
            CalendarDensity.YEAR -> yearHeight
        }

    fun stop(density: CalendarDensity): Float =
        when (density) {
            CalendarDensity.WEEK -> 0f
            CalendarDensity.MONTH -> monthStop
            CalendarDensity.YEAR -> yearStop
        }

    fun nearest(position: Float): CalendarDensity =
        when {
            position < monthStop / 2 -> CalendarDensity.WEEK
            position < (monthStop + yearStop) / 2 -> CalendarDensity.MONTH
            else -> CalendarDensity.YEAR
        }

    fun heightAt(position: Float): Float =
        if (position <= monthStop) {
            lerp(height(CalendarDensity.WEEK), height(CalendarDensity.MONTH), position / monthStop)
        } else {
            lerp(
                height(CalendarDensity.MONTH),
                yearHeight,
                (position - monthStop) / (yearStop - monthStop),
            )
        }

    fun yearProgress(position: Float) =
        ((position - monthStop) / (yearStop - monthStop)).coerceIn(0f, 1f)

    val miniLabelHeight = 20f * fontScale
    val miniGridTop = (yearRowHeight - miniLabelHeight - 6f - 78f) / 2 + miniLabelHeight + 6f
}

// The reference keeps the other eleven months veiled and blurred until the
// active month approaches its destination. They never duplicate the moving grid.
internal fun calendarYearOpacity(progress: Float): Float {
    val veil =
        if (progress <= 0.5f) 0.66f + 0.34f * (1 - 2 * progress).pow(6)
        else 0.66f * (1 - (2 * progress - 1).pow(6))
    return 1 - veil
}

private fun calendarYearBlur(progress: Float): Float {
    val p = progress.pow(1.65f)
    return 5f * (1 - p * p * p * (p * (p * 6 - 15) + 10))
}

@Composable
internal fun CalendarResizePreview(
    position: CalendarPosition,
    dragPosition: Float,
    marks: Set<LocalDate>,
    sizing: CalendarSizing,
    accessibleDays: @Composable (Boolean) -> Unit,
) {
    val month = position.visible
    val dates = remember(month) { calendarMonthDates(month) }
    val week =
        ChronoUnit.WEEKS.between(startOfWeek(month.atDay(1)), startOfWeek(position.selected))
            .toInt()
            .coerceIn(0, 5)
    val year = sizing.yearProgress(dragPosition)
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .height(sizing.heightAt(dragPosition).dp)
            .clipToBounds()
            .clearAndSetSemantics {}
    ) {
        if (dragPosition <= sizing.monthStop) {
            val expansion = dragPosition / sizing.monthStop
            if (sizing.largeText)
                accessibleDays(sizing.nearest(dragPosition) == CalendarDensity.WEEK)
            else
                Column {
                    WeekdayHeader(sizing.weekdayHeight)
                    Box(
                        Modifier.fillMaxWidth()
                            .height((sizing.heightAt(dragPosition) - sizing.weekdayHeight).dp)
                            .clipToBounds()
                    ) {
                        CalendarMonthContent(
                            dates,
                            month,
                            position.selected,
                            marks,
                            0f,
                            Modifier.offset(y = (-week * sizing.dayHeight * (1 - expansion)).dp)
                                .wrapContentHeight(Alignment.Top, unbounded = true)
                                .requiredHeight(sizing.monthHeight.dp),
                        )
                    }
                }
        } else {
            YearGrid(
                month.year,
                position.selected,
                sizing,
                hiddenMonth = if (sizing.largeText) null else month,
                modifier =
                    Modifier.wrapContentHeight(Alignment.Top, unbounded = true)
                        .requiredHeight(sizing.yearHeight.dp)
                        .blur(calendarYearBlur(year).dp)
                        .graphicsLayer { alpha = calendarYearOpacity(year) },
            ) {}
            if (sizing.largeText) {
                Box(Modifier.graphicsLayer { alpha = 1 - year }) { accessibleDays(false) }
            } else {
                Box(Modifier.graphicsLayer { alpha = (1 - year * 3).coerceAtLeast(0f) }) {
                    WeekdayHeader(sizing.weekdayHeight)
                }
                val monthIndex = month.monthValue - 1
                val columnWidth = maxWidth.value / 3
                val targetWidth = columnWidth - 14f
                val targetX = monthIndex % 3 * columnWidth + 7f
                val targetY = monthIndex / 3 * sizing.yearRowHeight + sizing.miniGridTop
                CalendarMonthContent(
                    dates,
                    month,
                    position.selected,
                    marks,
                    year,
                    Modifier.offset(
                            x = (targetX * year).dp,
                            y = lerp(sizing.weekdayHeight, targetY, year).dp,
                        )
                        .width(lerp(maxWidth.value, targetWidth, year).dp)
                        .height(lerp(sizing.monthHeight, 78f, year).dp),
                )
            }
        }
    }
}
