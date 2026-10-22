package com.roifewu.orgenda.core

import java.time.LocalDateTime

data class TimestampDraft(val original: OrgStamp, val date: LocalDateTime = original.date, val time: Boolean = original.hasTime, val recurrence: String = original.repeater?.token.orEmpty()) {
    val source get() = original.editing(date, time, recurrence)
}
data class PlanningDraft(val original: String, val keyword: String?, val timestamps: List<TimestampDraft>) {
    fun source(): String {
        val source = SourceText(original)
        val patches = timestamps.map { SourcePatch(it.original.start, it.original.end, it.source) }.toMutableList()
        val old = Regex("(?:SCHEDULED|DEADLINE|CLOSED)(?=:)").find(original)
        if (old != null && keyword != null) patches += SourcePatch(source.byteOffset(old.range.first), source.byteOffset(old.range.last + 1), keyword)
        return source.apply(patches)
    }
    companion object {
        fun parse(source: String): PlanningDraft? {
            val mapping = SourceText(source)
            val keyword = Regex("(?:SCHEDULED|DEADLINE|CLOSED)(?=:)").find(source)?.value
            val matches = Regex("<[^>\\r\\n]+>|\\[[^]\\r\\n]+]").findAll(source).toList()
            if (matches.size !in 1..2) return null
            val stamps = matches.map { match -> OrgStamp.parse(match.value, mapping.byteOffset(match.range.first), mapping.byteOffset(match.range.last + 1), keyword)?.let(::TimestampDraft) ?: return null }
            return PlanningDraft(source, keyword, stamps)
        }
    }
}
