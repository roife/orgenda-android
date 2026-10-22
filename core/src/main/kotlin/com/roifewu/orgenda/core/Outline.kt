package com.roifewu.orgenda.core

import java.time.LocalDate

data class OutlineFilter(
    val states: Set<String> = emptySet(),
    val scheduledFrom: LocalDate? = null,
    val scheduledTo: LocalDate? = null,
    val deadlineFrom: LocalDate? = null,
    val deadlineTo: LocalDate? = null,
) {
    val active
        get() =
            states.isNotEmpty() ||
                listOf(scheduledFrom, scheduledTo, deadlineFrom, deadlineTo).any { it != null }

    fun matches(item: OrgHeading): Boolean {
        fun date(stamp: OrgStamp?, from: LocalDate?, to: LocalDate?): Boolean {
            if (from == null && to == null) return true
            val day = stamp?.date?.toLocalDate() ?: return false
            return (from == null || day >= from) && (to == null || day <= to)
        }
        return (states.isEmpty() || item.state in states) &&
            date(item.scheduled, scheduledFrom, scheduledTo) &&
            date(item.deadline, deadlineFrom, deadlineTo)
    }

    fun apply(headings: List<OrgHeading>): List<OrgHeading> {
        val matched = headings.filter(::matches)
        return headings.filter { item ->
            matched.any { it.start in item.start until item.subtreeEnd }
        }
    }
}

enum class HeadingPlacement {
    BEFORE,
    AFTER,
    CHILD,
}

data class HeadingMove(val text: String, val starts: Map<Int, Int>)

object Outline {
    fun keys(document: OrgDocument): Map<Int, String> {
        val counts = mutableMapOf<String, Int>()
        return document.headings.associate { heading ->
            val base =
                (heading.ancestors + heading.title).joinToString("/") {
                    "${it.utf8Bytes().size}:$it"
                }
            val ordinal = counts.getOrDefault(base, 0)
            counts[base] = ordinal + 1
            heading.start to "$base#$ordinal"
        }
    }

    fun move(
        document: OrgDocument,
        item: OrgHeading,
        target: OrgHeading,
        placement: HeadingPlacement,
    ): HeadingMove {
        require(
            item.sourceHash == document.text.sha256() &&
                target.sourceHash == item.sourceHash &&
                item.path == target.path
        )
        require(target.start !in item.start until item.subtreeEnd) {
            "A heading cannot move into its own subtree"
        }
        val insertion =
            if (placement == HeadingPlacement.BEFORE) target.start else target.subtreeEnd
        val delta = target.level + (if (placement == HeadingPlacement.CHILD) 1 else 0) - item.level
        val lower = minOf(insertion, item.start)
        val upper = maxOf(insertion, item.subtreeEnd)
        document.syntax.requireSafe(lower, upper)
        val subtree = item.start until item.subtreeEnd
        val ranges =
            (if (insertion <= item.start) listOf(subtree, insertion until item.start)
                else listOf(item.subtreeEnd until insertion, subtree))
                .filterNot { it.isEmpty() }
        val output = StringBuilder()
        val relocated = mutableMapOf<Int, Int>()
        for ((index, range) in ranges.withIndex()) {
            val changed = if (range == subtree) delta else 0
            val headings = document.headings.filter { it.start in range }
            val source = SourceText(document.syntax.source.slice(range.first, range.last + 1))
            val text =
                source.apply(
                    headings.map { heading ->
                        SourcePatch(
                            heading.start - range.first,
                            heading.start - range.first + heading.level,
                            "*".repeat(heading.level + changed),
                        )
                    }
                )
            val start = lower + output.toString().utf8Bytes().size
            headings.forEachIndexed { ordinal, heading ->
                relocated[heading.start] = start + heading.start - range.first + ordinal * changed
            }
            output.append(text)
            if (
                !text.endsWith('\n') &&
                    (index < ranges.lastIndex || upper < document.syntax.source.bytes.size)
            )
                output.append(document.syntax.source.newline)
        }
        val text =
            document.syntax.source.apply(listOf(SourcePatch(lower, upper, output.toString())))
        val sizeChange = text.utf8Bytes().size - document.syntax.source.bytes.size
        document.headings
            .filter { it.start !in relocated }
            .forEach { relocated[it.start] = it.start + if (it.start >= upper) sizeChange else 0 }
        return HeadingMove(text, relocated)
    }
}
