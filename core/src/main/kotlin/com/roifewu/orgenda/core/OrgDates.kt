package com.roifewu.orgenda.core

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

val orgDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE", Locale.US)

fun orgTimestamp(
    date: LocalDateTime,
    active: Boolean = true,
    includesTime: Boolean = true,
): String =
    (if (active) "<" else "[") +
        date.format(orgDateFormat) +
        (if (includesTime) date.format(DateTimeFormatter.ofPattern(" HH:mm")) else "") +
        (if (active) ">" else "]")

data class OrgRepeater(
    val mode: String,
    val interval: Long,
    val unit: Char,
    val maximum: String = "",
) {
    val token
        get() = "$mode$interval$unit$maximum"

    private fun occurrence(base: ZonedDateTime, n: Long): ZonedDateTime {
        val amount = Math.multiplyExact(interval, n)
        return when (unit) {
            'h' -> base.plusHours(amount)
            'd' -> base.plusDays(amount)
            'w' -> base.plusWeeks(amount)
            'm' -> base.plusMonths(amount)
            'y' -> base.plusYears(amount)
            else -> error("Invalid repeater")
        }
    }

    fun next(base: ZonedDateTime, completion: ZonedDateTime): ZonedDateTime =
        when (mode) {
            "+" -> occurrence(base, 1)
            ".+" ->
                if (unit == 'h') occurrence(completion.withSecond(0).withNano(0), 1)
                else
                    occurrence(
                        completion.toLocalDate().atTime(base.toLocalTime()).atZone(base.zone),
                        1,
                    )
            else -> {
                var lower = 0L
                var upper = 1L
                while (!occurrence(base, upper).isAfter(completion)) {
                    lower = upper
                    upper = Math.multiplyExact(upper, 2)
                }
                while (upper - lower > 1) {
                    val mid = lower + (upper - lower) / 2
                    if (occurrence(base, mid).isAfter(completion)) upper = mid else lower = mid
                }
                occurrence(base, upper)
            }
        }

    companion object {
        private val regex = Regex("(\\+\\+|\\.\\+|\\+)([1-9][0-9]*)([hdwmy])(/[1-9][0-9]*[hdwmy])?")

        fun parse(token: String): OrgRepeater? =
            regex.matchEntire(token)?.let { m ->
                if (
                    m.groupValues[4].isNotEmpty() &&
                        m.groupValues[4].drop(1).dropLast(1).toLongOrNull() == null
                )
                    return null
                m.groupValues[2].toLongOrNull()?.let {
                    OrgRepeater(m.groupValues[1], it, m.groupValues[3][0], m.groupValues[4])
                }
            }
    }
}

data class OrgStamp(
    val start: Int,
    val end: Int,
    val raw: String,
    val date: LocalDateTime,
    val hasTime: Boolean,
    val active: Boolean,
    val keyword: String?,
    val repeater: OrgRepeater?,
    val rangeEnd: LocalDateTime? = null,
) {
    val durationMinutes: Long
        get() =
            rangeEnd?.let { Duration.between(date, it).toMinutes().coerceAtLeast(0) }
                ?: if (hasTime) 30 else 0

    fun replacingDate(next: LocalDateTime, time: Boolean = hasTime): String {
        val datePart = Regex("([0-9]{4}-[0-9]{2}-[0-9]{2})(?:([ \\t]+)([A-Za-z]+))?")
        if (raw.contains("--")) {
            val pieces = raw.split("--", limit = 2)
            val first = parse(pieces[0], 0, 0) ?: return raw
            val second = parse(pieces[1], 0, 0) ?: return raw
            val shift = Duration.between(first.date, next)
            return first.replacingDate(next, time) +
                "--" +
                second.replacingDate(second.date.plus(shift), time)
        }
        val day = datePart.find(raw) ?: return raw
        val oldWeekday = day.groupValues[3]
        val weekday =
            if (next.toLocalDate() == date.toLocalDate()) oldWeekday
            else
                next
                    .format(
                        DateTimeFormatter.ofPattern(
                            if (oldWeekday.length > 3) "EEEE" else "EEE",
                            Locale.US,
                        )
                    )
                    .let {
                        when {
                            oldWeekday == oldWeekday.uppercase() -> it.uppercase()
                            oldWeekday == oldWeekday.lowercase() -> it.lowercase()
                            else -> it
                        }
                    }
        var changed =
            raw.replaceRange(
                day.range,
                next.toLocalDate().toString() +
                    if (day.groupValues[3].isNotEmpty()) day.groupValues[2] + weekday else "",
            )
        val clock = Regex("([ \\t]+)([0-9]{2}:[0-9]{2})(?:-([0-9]{2}:[0-9]{2}))?")
        val existing = clock.find(changed)
        if (time) {
            val start = next.format(DateTimeFormatter.ofPattern("HH:mm"))
            val end =
                existing
                    ?.groupValues
                    ?.get(3)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        val duration =
                            Math.floorMod(
                                Duration.between(
                                        LocalTime.parse(existing.groupValues[2]),
                                        LocalTime.parse(it),
                                    )
                                    .toMinutes(),
                                1440L,
                            )
                        "-" +
                            next.plusMinutes(duration).format(DateTimeFormatter.ofPattern("HH:mm"))
                    } ?: ""
            val text = (existing?.groupValues?.get(1) ?: " ") + start + end
            changed =
                if (existing != null) changed.replaceRange(existing.range, text)
                else {
                    val dateEnd = datePart.find(changed)!!.range.last + 1
                    changed.substring(0, dateEnd) + text + changed.substring(dateEnd)
                }
        } else if (existing != null) changed = changed.removeRange(existing.range)
        return changed
    }

    fun editing(next: LocalDateTime, time: Boolean, recurrence: String): String {
        require(recurrence.isEmpty() || OrgRepeater.parse(recurrence) != null)
        val moved = replacingDate(next, time)
        val token =
            Regex(
                    "(?<!\\S)(?:\\+\\+|\\.\\+|\\+)[1-9][0-9]*[hdwmy](?:/[1-9][0-9]*[hdwmy])?(?=[ \\t>\\]])"
                )
                .find(moved)
        if (token != null) {
            val start =
                if (
                    recurrence.isEmpty() &&
                        token.range.first > 0 &&
                        moved[token.range.first - 1] in " \t"
                )
                    token.range.first - 1
                else token.range.first
            return moved.replaceRange(start, token.range.last + 1, recurrence)
        }
        if (recurrence.isEmpty()) return moved
        val beforeWarning =
            Regex("[ \\t]+-[0-9]+[hdwmy]").find(moved)?.range?.first
                ?: moved.indexOfFirst { it == '>' || it == ']' }
        return moved.replaceRange(beforeWarning, beforeWarning, " $recurrence")
    }

    companion object {
        private val date = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        private val time = Regex("(?<![0-9])[0-9]{2}:[0-9]{2}")
        private val repeat =
            Regex("(?:\\+\\+|\\.\\+|\\+)[1-9][0-9]*[hdwmy](?:/[1-9][0-9]*[hdwmy])?")

        fun parse(raw: String, start: Int, end: Int, keyword: String? = null): OrgStamp? {
            return try {
                val day = LocalDate.parse(date.find(raw)?.value ?: return null)
                val first = raw.substringBefore("--")
                val clock = time.find(first)?.value?.let(LocalTime::parse)
                val startDate = day.atTime(clock ?: LocalTime.MIDNIGHT)
                val endDate =
                    if (raw.contains("--")) parse(raw.substringAfter("--"), 0, 0)?.date
                    else
                        time.findAll(first).drop(1).firstOrNull()?.value?.let {
                            var endTime = day.atTime(LocalTime.parse(it))
                            if (endTime < startDate) endTime = endTime.plusDays(1)
                            endTime
                        }
                OrgStamp(
                    start,
                    end,
                    raw,
                    startDate,
                    clock != null,
                    raw.startsWith('<'),
                    keyword,
                    repeat.find(first)?.value?.let(OrgRepeater::parse),
                    endDate,
                )
            } catch (_: DateTimeException) {
                null
            }
        }
    }
}
