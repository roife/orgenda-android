package com.roifewu.orgenda.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CaptureExpansion(val start: Int, val end: Int, val token: String, val prompt: String? = null, val choices: List<String> = emptyList())

object CaptureEngine {
    fun expansions(template: String): List<CaptureExpansion> {
        val source = SourceText(template)
        val result = mutableListOf<CaptureExpansion>()
        var cursor = 0
        while (cursor < template.length) {
            if (template[cursor++] != '%') continue
            val start = cursor - 1
            require(cursor < template.length) { "Incomplete capture placeholder" }
            val type = template[cursor++]
            var prompt: String? = null; var choices = emptyList<String>()
            when (type) {
                '^' -> {
                    require(cursor < template.length && template[cursor] == '{') { "Expected %^{Prompt|Default|Choices}" }
                    val end = template.indexOf('}', cursor)
                    require(end >= 0) { "Unclosed capture prompt" }
                    val parts = template.substring(cursor + 1, end).split('|')
                    prompt = parts.first(); choices = parts.drop(1); cursor = end + 1
                    if (cursor < template.length && template[cursor] in "tTuU") cursor++
                }
                '<' -> {
                    val end = template.indexOf('>', cursor)
                    require(end >= 0)
                    val format = template.substring(cursor, end)
                    require(!format.replace(Regex("%[YmdHMaAbB%]"), "").contains('%')) { "Unsupported date format" }
                    cursor = end + 1
                }
                else -> require(type in "%?tTuUaix") { "Unsupported capture placeholder %$type. Scripts are not executed." }
            }
            result += CaptureExpansion(source.byteOffset(start), source.byteOffset(cursor), template.substring(start, cursor), prompt, choices)
        }
        return result
    }
    fun render(template: String, answers: Map<Int, String> = emptyMap(), cursorText: String = "", date: LocalDateTime = LocalDateTime.now(), link: String = "", selection: String = "", clipboard: String = ""): String {
        val patches = expansions(template).map { e ->
            val value = if (e.prompt != null) answers[e.start] ?: e.choices.firstOrNull() ?: error("Answer required: ${e.prompt}") else when (e.token) {
                "%%" -> "%"; "%?" -> cursorText; "%a" -> link; "%i" -> selection; "%x" -> clipboard
                "%t", "%T", "%u", "%U" -> orgTimestamp(date, e.token[1] in "tT", e.token[1] in "TU")
                else -> formatDate(date, e.token.substring(2, e.token.length - 1))
            }
            SourcePatch(e.start, e.end, value)
        }
        return SourceText(template).apply(patches)
    }
    private fun formatDate(date: LocalDateTime, format: String): String {
        val mapping = mapOf('Y' to "yyyy", 'm' to "MM", 'd' to "dd", 'H' to "HH", 'M' to "mm", 'a' to "EEE", 'A' to "EEEE", 'b' to "MMM", 'B' to "MMMM")
        return Regex("%[YmdHMaAbB%]").replace(format) { m -> mapping[m.value[1]]?.let { date.format(DateTimeFormatter.ofPattern(it, Locale.US)) } ?: "%" }
    }
    fun insert(rendered: String, template: CaptureTemplate, document: OrgDocument, date: LocalDateTime = LocalDateTime.now(), createMissing: Boolean = false): String {
        var outline = template.target.outline
        if (template.target.type == "datetree") outline += listOf(date.year.toString(), formatDate(date, "%Y-%m"), formatDate(date, "%Y-%m-%d %A"))
        var lower = 0; var upper = document.syntax.source.bytes.size; var level = 0; var parent: OrgHeading? = null
        val nl = document.syntax.source.newline
        for (title in outline) {
            val candidates = document.headings.filter { it.start in lower until upper && it.level == level + 1 && it.title == title }
            require(candidates.size <= 1) { "Ambiguous capture target: $title" }
            val match = candidates.firstOrNull()
            if (match == null) {
                require(createMissing) { "Missing capture target: $title" }
                document.syntax.requireSafe(lower, upper)
                val prefix = outline.drop(level).mapIndexed { index, name -> "*".repeat(level + index + 1) + " " + name }.joinToString("\n") + "\n"
                val body = adjusted(rendered, template.type, outline.size + 1)
                val insertion = ((if (upper > 0) "\n" else "") + prefix + body + "\n").replace("\r\n", "\n").replace("\n", nl)
                return document.syntax.source.apply(listOf(SourcePatch(upper, upper, insertion)))
            }
            parent = match; level++; lower = match.end; upper = match.subtreeEnd
        }
        document.syntax.requireSafe(lower, upper)
        var insertion = if (template.prepend) parent?.end ?: 0 else upper
        if (template.prepend && parent != null) {
            insertion = document.syntax.blocks.filter { it.start >= parent.end && it.start < upper }
                .takeWhile { it.type in listOf("planning", "property_drawer", "drawer", "blank_line") }.lastOrNull()?.end ?: parent.end
        } else if (template.prepend) insertion = document.headings.firstOrNull()?.start ?: document.syntax.source.bytes.size
        val body = adjusted(rendered, template.type, level + 1)
        val spacing = "\n".repeat(template.emptyLines)
        val before = document.syntax.source.slice(0, insertion)
        val value = ((if (before.isEmpty() || before.endsWith('\n')) "" else "\n") + spacing + body + "\n" + spacing).replace("\r\n", "\n").replace("\n", nl)
        return document.syntax.source.apply(listOf(SourcePatch(insertion, insertion, value)))
    }
    private fun adjusted(source: String, type: String, level: Int): String = when (type) {
        "plain" -> source
        "item" -> if (source.startsWith("- ")) source else "- $source"
        "checkitem" -> if (source.startsWith("- [")) source else "- [ ] $source"
        else -> {
            require(source.startsWith("* "))
            OrgParser().use { parser ->
                val doc = parser.parse(source)
                doc.requireSafe(0, doc.source.bytes.size)
                doc.source.apply(doc.blocks.filter { it.type == "heading" }.map {
                    val count = doc.text(it).takeWhile { ch -> ch == '*' }.length
                    SourcePatch(it.start, it.start + count, "*".repeat(count + level - 1))
                })
            }
        }
    }
}
