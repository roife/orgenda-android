package com.roifewu.orgenda.core

data class InputEdit(val text: String, val start: Int, val end: Int)

enum class InputCommand {
    HEADING,
    CHECKBOX,
    INDENT,
    OUTDENT,
}

object OrgInputCommands {
    private val list =
        Regex("^([ \\t]*)([-+]|[0-9]+[.)])([ \\t]+)(?:\\[([ Xx-])\\]([ \\t]*))?(.*)$")

    fun apply(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        command: InputCommand,
    ): InputEdit {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, text.length)
        val firstLine = text.lastIndexOf('\n', start - 1) + 1
        val lastPosition = if (end > start) end - 1 else end
        val changes = mutableListOf<Triple<Int, Int, String>>()
        var at = firstLine
        while (at <= lastPosition) {
            val next = text.indexOf('\n', at).let { if (it < 0) text.length else it }
            val line = text.substring(at, next).removeSuffix("\r")
            val heading = Regex("^\\*+[ \\t]+").find(line)
            when (command) {
                InputCommand.HEADING -> changes += Triple(at, 0, if (heading == null) "* " else "*")
                InputCommand.CHECKBOX -> {
                    val match = list.matchEntire(line)
                    if (match == null) changes += Triple(at, 0, "- [ ] ")
                    else if (match.groupValues[4].isEmpty())
                        changes += Triple(at + match.groups[3]!!.range.last + 1, 0, "[ ] ")
                }
                InputCommand.INDENT -> changes += Triple(at, 0, if (heading == null) "  " else "*")
                InputCommand.OUTDENT -> {
                    val count =
                        if (heading != null) if (line.startsWith("**")) 1 else 0
                        else if (line.startsWith('\t')) 1
                        else line.takeWhile { it == ' ' }.length.coerceAtMost(2)
                    if (count > 0) changes += Triple(at, count, "")
                }
            }
            if (next == text.length) break
            at = next + 1
        }
        fun mapped(position: Int): Int =
            position +
                changes.sumOf { (at, removed, added) ->
                    if (at <= position) added.length - minOf(removed, position - at) else 0
                }
        var result = text
        changes.asReversed().forEach { (at, removed, added) ->
            result = result.replaceRange(at, at + removed, added)
        }
        return InputEdit(result, mapped(start), mapped(end))
    }

    fun newline(text: String, start: Int, end: Int): InputEdit? {
        if (start != end || start !in 0..text.length) return null
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val match = list.matchEntire(text.substring(lineStart, start)) ?: return null
        val syntax = OrgParser().use { it.parse(text) }
        val byte = syntax.source.byteOffset(lineStart)
        if (
            syntax.blocks.none {
                it.type == "list" && !it.error && byte >= it.start && byte < it.end
            }
        )
            return null
        val indent = match.groupValues[1]
        val marker = match.groupValues[2]
        if (match.groupValues[6].isBlank())
            return InputEdit(text.removeRange(lineStart, start), lineStart, lineStart)
        val nextMarker =
            if (marker.first().isDigit()) {
                val value = marker.dropLast(1).toLongOrNull() ?: return null
                if (value == Long.MAX_VALUE) return null
                "${value + 1}${marker.last()}"
            } else marker
        val insertion =
            syntax.source.newline +
                indent +
                nextMarker +
                match.groupValues[3] +
                if (match.groupValues[4].isNotEmpty()) "[ ] " else ""
        return InputEdit(
            text.replaceRange(start, start, insertion),
            start + insertion.length,
            start + insertion.length,
        )
    }
}
