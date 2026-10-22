package com.roifewu.orgenda.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun CalendarDateHeading(date: LocalDate, month: YearMonth, yearOnly: Boolean) {
    val locale = Locale.getDefault()
    val showYear = date.year != LocalToday.current.date.year
    val pattern = when {
        locale.language == "zh" -> if (showYear) "yyyy年M月d日" else "M月d日"
        showYear -> "MMM d, yyyy"
        else -> "MMM d"
    }
    val weekdayStyle = SpanStyle(
        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val label = buildAnnotatedString {
        if (yearOnly) append(month.year.toString())
        else {
            append(date.format(DateTimeFormatter.ofPattern(pattern, locale)))
            withStyle(weekdayStyle) {
                append(" · ")
                append(date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale))
            }
        }
    }
    Text(label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}
