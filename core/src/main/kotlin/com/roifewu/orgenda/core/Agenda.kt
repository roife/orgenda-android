package com.roifewu.orgenda.core

import java.time.LocalDate
import java.time.ZoneId

object Agenda {
    fun on(items: List<OrgHeading>, day: LocalDate): List<OrgHeading> = items.mapNotNull { project(it, day) }.sortedBy { it.agendaStamp?.date }

    fun project(item: OrgHeading, day: LocalDate): OrgHeading? {
        if (item.done) return null
        val primary = item.agendaStamp ?: return null
        if (listOfNotNull(item.scheduled, item.deadline, item.event).any { it.date.toLocalDate() == day }) return item
        val occurrence = primary.repeater?.let { repeater ->
            runCatching {
                if (day < primary.date.toLocalDate()) null else {
                    val zone = ZoneId.systemDefault()
                    repeater.copy(mode = "++").next(primary.date.atZone(zone), day.atStartOfDay(zone).minusNanos(1)).toLocalDateTime().takeIf { it.toLocalDate() == day }
                }
            }.getOrNull()
        }
        if (occurrence != null) return item.copy(stamps = item.stamps.map { if (it == primary) it.copy(date = occurrence, raw = it.replacingDate(occurrence)) else it })
        val event = item.event
        if (event != null && event.durationMinutes >= 1440 && day >= event.date.toLocalDate() && day <= event.date.plusMinutes(event.durationMinutes).toLocalDate()) return item
        return null
    }

    fun overdue(item: OrgHeading, today: LocalDate) = item.isTask && !item.done && item.agendaStamp?.date?.toLocalDate()?.isBefore(today) == true
    val priorityOrder = compareBy<OrgHeading> { it.priority.ifEmpty { "B" } }.thenBy { it.path }.thenBy { it.start }
}
