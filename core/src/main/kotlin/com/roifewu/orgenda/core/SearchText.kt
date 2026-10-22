package com.roifewu.orgenda.core

import java.text.Normalizer
import java.util.Locale

object SearchText {
    private fun fold(value: String) =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)

    fun range(source: String, query: String): IntRange? = ranges(source, query, 1).firstOrNull()

    fun ranges(source: String, query: String, limit: Int = Int.MAX_VALUE): List<IntRange> {
        val needle = fold(query.trim())
        if (needle.isEmpty()) return emptyList()
        val text = StringBuilder()
        val positions = mutableListOf<Int>()
        var at = 0
        while (at < source.length) {
            val size = Character.charCount(source.codePointAt(at))
            val value = fold(source.substring(at, at + size))
            text.append(value)
            repeat(value.length) { positions += at }
            at += size
        }
        val matches = mutableListOf<IntRange>()
        var cursor = 0
        while (matches.size < limit) {
            val index = text.indexOf(needle, cursor)
            if (index < 0) break
            val start = positions[index]
            val last = positions[index + needle.length - 1]
            var end = last + Character.charCount(source.codePointAt(last))
            while (
                end < source.length &&
                    Character.getType(source.codePointAt(end)) in
                        listOf(
                            Character.NON_SPACING_MARK.toInt(),
                            Character.COMBINING_SPACING_MARK.toInt(),
                            Character.ENCLOSING_MARK.toInt(),
                        )
            ) end += Character.charCount(source.codePointAt(end))
            matches += start until end
            cursor = index + needle.length
        }
        return matches
    }

    fun matches(source: String, query: String): Boolean {
        val needle = fold(query.trim())
        return needle.isNotEmpty() && fold(source).contains(needle)
    }

    fun excerpt(source: String, query: String): String {
        val range = range(source, query)
        var start = (range?.first?.minus(45) ?: 0).coerceAtLeast(0)
        var end = (range?.last?.plus(111) ?: 160).coerceAtMost(source.length)
        if (start > 0 && source[start].isLowSurrogate()) start--
        if (end < source.length && source[end].isLowSurrogate()) end++
        return (if (start > 0) "…" else "") +
            source.substring(start, end).trim().replace(Regex("\\s+"), " ") +
            if (end < source.length) "…" else ""
    }
}
