package com.roifewu.orgenda.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class JournalEntry(val id: String, val path: String, val start: Int, val end: Int, val date: LocalDateTime, val title: String, val body: String)

object JournalIndex {
    fun entries(document: OrgDocument, directory: String): List<JournalEntry> {
        val folder = java.nio.file.Paths.get(directory).normalize().toString().trimEnd('/')
        if (!document.path.startsWith("$folder/") && !(folder == "journal" && document.path.split('/').dropLast(1).any { it.equals("journal", true) })) return emptyList()
        val filename = document.path.substringAfterLast('/').substringBeforeLast('.')
        val filenameDay = runCatching { LocalDate.parse(filename, if (filename.length == 8) DateTimeFormatter.BASIC_ISO_DATE else DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
        var currentDay = filenameDay
        val counts = mutableMapOf<String, Int>()
        val result = mutableListOf<JournalEntry>()
        fun append(day: LocalDate, title: String, body: String, start: Int, end: Int) {
            val clock = Regex("^([0-9]{2}:[0-9]{2})(?:[ \\t]+|$)(.*)").matchEntire(title)
            val time = clock?.let { runCatching { LocalTime.parse(it.groupValues[1]) }.getOrNull() }
            val cleanTitle = (if (time != null) clock.groupValues[2] else title).ifBlank { "Journal entry" }
            val key = "${document.path}\u0000$day\u0000$title"
            val ordinal = counts.getOrDefault(key, 0); counts[key] = ordinal + 1
            result += JournalEntry("$key\u0000$ordinal".sha256(), document.path, start, end, day.atTime(time ?: LocalTime.MIDNIGHT), cleanTitle, body.trim())
        }
        document.headings.forEachIndexed { index, heading ->
            if (heading.level == 1) {
                val date = Regex("^([0-9]{4}-[0-9]{2}-[0-9]{2})(?=\\s|$)").find(heading.title)?.groupValues?.get(1)
                currentDay = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: filenameDay
            }
            val day = currentDay ?: return@forEachIndexed
            if (heading.level > 2) return@forEachIndexed
            val children = document.headings.drop(index + 1).takeWhile { it.level > heading.level }
            if (heading.level == 1 && children.any { it.level == 2 }) return@forEachIndexed
            val body = document.syntax.source.slice(heading.end, heading.subtreeEnd).trim()
            if (heading.level == 1 && body.isEmpty()) return@forEachIndexed
            append(day, heading.title, body, heading.start, heading.subtreeEnd)
        }
        if (document.headings.isEmpty() && filenameDay != null && document.text.isNotBlank()) append(filenameDay, "", document.text, 0, document.syntax.source.bytes.size)
        return result
    }

    fun append(document: OrgDocument, date: LocalDateTime, title: String, body: String): String {
        require(body.isNotBlank())
        require(title.none { it == '\n' || it == '\r' })
        val newline = document.syntax.source.newline
        val entry = "* ${date.format(orgDateFormat)}\n** ${date.format(DateTimeFormatter.ofPattern("HH:mm"))} ${title.trim().ifEmpty { "Journal entry" }}\n$body"
            .replace("\r\n", "\n").replace("\n", newline)
        return document.text + (if (document.text.isEmpty()) "" else newline + newline) + entry
    }
}
