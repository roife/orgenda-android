package com.roifewu.orgenda.core

import java.time.*

data class OrgHeading(
    val path: String,
    val start: Int,
    val end: Int,
    val bodyEnd: Int,
    val subtreeEnd: Int,
    val level: Int,
    val title: String,
    val state: String,
    val priority: String,
    val tags: List<String>,
    val titleStart: Int,
    val titleEnd: Int,
    val stateStart: Int,
    val stateEnd: Int,
    val priorityStart: Int,
    val priorityEnd: Int,
    val tagsStart: Int,
    val tagsEnd: Int,
    val stamps: List<OrgStamp>,
    val properties: Map<String, String>,
    val workflow: Workflow,
    val sourceHash: String,
    val ancestors: List<String> = emptyList(),
    val completedDays: Set<LocalDate> = emptySet(),
    val body: String = "",
    val sectionHash: String = "",
) {
    val id
        get() = "$path:$start"

    val done
        get() = workflow.terminal(state)

    val isTask
        get() = state.isNotEmpty()

    val habit
        get() = "habit" in tags || properties["STYLE"]?.equals("habit", true) == true

    val canComplete
        get() = isTask || agendaStamp?.repeater != null

    val event
        get() = stamps.firstOrNull { it.active && it.keyword == null }

    val kind
        get() =
            when {
                "event" in tags -> "event"
                "note" in tags -> "note"
                "project" in tags -> "project"
                habit -> "habit"
                !isTask && agendaStamp != null -> "event"
                else -> "task"
            }

    val scheduled
        get() = stamps.firstOrNull { it.keyword == "SCHEDULED" }

    val deadline
        get() = stamps.firstOrNull { it.keyword == "DEADLINE" }

    val agendaStamp
        get() = scheduled ?: deadline ?: event
}

data class OrgDocument(
    val path: String,
    val syntax: SyntaxDocument,
    val headings: List<OrgHeading>,
    val workflow: Workflow,
) {
    val text
        get() = syntax.source.text

    fun heading(start: Int) = headings.first { it.start == start }
}

interface OrgEngine {
    fun parse(
        path: String,
        text: String,
        config: WorkspaceConfig = WorkspaceConfig.defaults(),
    ): OrgDocument

    fun changeItem(
        document: OrgDocument,
        item: OrgHeading,
        title: String = item.title,
        state: String = item.state,
        priority: String = item.priority,
        tags: List<String> = item.tags,
    ): String

    fun complete(
        document: OrgDocument,
        item: OrgHeading,
        config: WorkspaceConfig,
        now: ZonedDateTime = ZonedDateTime.now(),
        note: String = "",
    ): String
}

class NativeOrgEngine : OrgEngine, AutoCloseable {
    private data class CacheEntry(
        val parser: OrgParser,
        var document: OrgDocument?,
        var config: WorkspaceConfig?,
    )

    private val cache = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)

    @Synchronized
    override fun parse(path: String, text: String, config: WorkspaceConfig): OrgDocument {
        val entry = cache.getOrPut(path) { CacheEntry(OrgParser(), null, null) }
        if (entry.document?.text == text && entry.config == config) return entry.document!!
        val syntax = entry.parser.parse(text)
        val workflow = localWorkflow(syntax, config.workflow)
        val headingNodes = syntax.blocks.filter { it.type == "heading" }
        val hash = text.sha256()
        val headings = headingNodes.mapIndexed { index, node ->
            val source = syntax.text(node)
            val level = source.takeWhile { it == '*' }.length
            val bodyEnd = headingNodes.getOrNull(index + 1)?.start ?: syntax.source.bytes.size
            val subtreeEnd =
                headingNodes
                    .drop(index + 1)
                    .firstOrNull { syntax.text(it).takeWhile { ch -> ch == '*' }.length <= level }
                    ?.start ?: syntax.source.bytes.size
            val local = SourceText(source)
            var position = level
            while (position < source.length && source[position] in " \t") position++
            val stateStart = position
            val token = source.substring(position).takeWhile { !it.isWhitespace() }
            val state = token.takeIf { it in workflow.tokens } ?: ""
            if (state.isNotEmpty()) {
                position += token.length
                while (position < source.length && source[position] in " \t") position++
            }
            val stateEnd = position
            val priorityStart = position
            val match = Regex("^\\[#([A-Z]|[0-9]{1,2})\\][ \\t]*").find(source.substring(position))
            val priority = match?.groupValues?.get(1) ?: ""
            if (match != null) position += match.value.length
            val priorityEnd = position
            val tagsNode = syntax.children(node).firstOrNull { it.type == "tag_list" }
            val tagsStart =
                tagsNode?.start
                    ?: node.end -
                        source.takeLastWhile { it == '\n' || it == '\r' }.toByteArray().size
            val tagsEnd = tagsNode?.end ?: tagsStart
            var titleEnd = tagsStart
            while (
                titleEnd > node.start + local.byteOffset(position) &&
                    syntax.source.bytes[titleEnd - 1].toInt() in listOf(32, 9, 10, 13)
            ) titleEnd--
            val titleStart = node.start + local.byteOffset(position)
            val body = syntax.blocks.filter { it.start >= node.end && it.start < bodyEnd }
            val stamps = body.flatMap { b ->
                if (b.type !in listOf("planning", "paragraph", "list")) emptyList()
                else {
                    val descendants = syntax.descendants(b)
                    val ranges = descendants.filter { it.type == "timestamp_range" }
                    descendants
                        .filter {
                            it.type == "timestamp_range" ||
                                it.type in listOf("active_timestamp", "inactive_timestamp") &&
                                    ranges.none { range ->
                                        it.start >= range.start && it.end <= range.end
                                    }
                        }
                        .mapNotNull { stamp ->
                            val planning =
                                if (b.type == "planning")
                                    syntax.descendants(b).firstOrNull {
                                        it.type == "planning_entry" &&
                                            stamp.start >= it.start &&
                                            stamp.end <= it.end
                                    }
                                else null
                            OrgStamp.parse(
                                syntax.text(stamp),
                                stamp.start,
                                stamp.end,
                                planning?.let { syntax.text(it).substringBefore(':') },
                            )
                        }
                }
            }
            val properties =
                body
                    .filter { it.type == "property_drawer" }
                    .flatMap { b ->
                        syntax
                            .descendants(b)
                            .filter { it.type == "property" }
                            .mapNotNull {
                                Regex("^[ \\t]*:([A-Za-z0-9_@#%+.-]+):[ \\t]*(.*)")
                                    .find(syntax.text(it))
                                    ?.let { m ->
                                        m.groupValues[1].uppercase() to m.groupValues[2].trim()
                                    }
                            }
                    }
                    .toMap()
            val logs =
                body
                    .filter { it.type == "drawer" || it.type == "list" }
                    .joinToString("\n") { syntax.text(it) }
            val completed =
                Regex("State \"([^\"]+)\"[^\\r\\n]*?\\[([0-9]{4}-[0-9]{2}-[0-9]{2})")
                    .findAll(logs)
                    .filter { workflow.terminal(it.groupValues[1]) }
                    .mapNotNull { runCatching { LocalDate.parse(it.groupValues[2]) }.getOrNull() }
                    .toSet() +
                    stamps.filter { it.keyword == "CLOSED" }.map { it.date.toLocalDate() } +
                    listOfNotNull(
                        properties["LAST_REPEAT"]?.let {
                            OrgStamp.parse(it, 0, it.toByteArray().size)?.date?.toLocalDate()
                        }
                    )
            OrgHeading(
                path,
                node.start,
                node.end,
                bodyEnd,
                subtreeEnd,
                level,
                syntax.source.slice(titleStart, titleEnd),
                state,
                priority,
                tagsNode?.let { syntax.text(it).trim().trim(':').split(':') } ?: emptyList(),
                titleStart,
                titleEnd,
                node.start + local.byteOffset(stateStart),
                node.start + local.byteOffset(stateEnd),
                node.start + local.byteOffset(priorityStart),
                node.start + local.byteOffset(priorityEnd),
                tagsStart,
                tagsEnd,
                stamps,
                properties,
                workflow,
                hash,
                completedDays = completed,
                body =
                    editableBody(syntax, node.end, bodyEnd)
                        .joinToString("\n") { syntax.text(it) }
                        .trim(),
                sectionHash = syntax.source.slice(node.start, bodyEnd).sha256(),
            )
        }
        val ancestors = mutableListOf<OrgHeading>()
        val projected = headings.map { h ->
            while (ancestors.lastOrNull()?.level?.let { it >= h.level } == true) ancestors.removeAt(
                ancestors.lastIndex
            )
            h.copy(ancestors = ancestors.map { it.title }).also { ancestors += it }
        }
        val doc = OrgDocument(path, syntax, projected, workflow)
        entry.document = doc
        entry.config = config
        var bytes = cache.values.sumOf { it.document?.syntax?.source?.bytes?.size?.toLong() ?: 0 }
        val it = cache.entries.iterator()
        while ((cache.size > 128 || bytes > 32L * 1024 * 1024) && cache.size > 1) {
            val oldest = it.next().value
            bytes -= oldest.document?.syntax?.source?.bytes?.size ?: 0
            oldest.parser.close()
            it.remove()
        }
        return doc
    }

    private fun localWorkflow(syntax: SyntaxDocument, base: Workflow): Workflow {
        val sequences = mutableListOf<WorkflowSequence>()
        val styles = base.keywords.toMutableMap()
        for (node in syntax.blocks) {
            if (node.error || node.type == "orphan_drawer_delimiter") break
            if (node.type != "keyword") continue
            val match =
                Regex("^#\\+(?:TODO|SEQ_TODO|TYP_TODO):[ \\t]*(.*)", RegexOption.IGNORE_CASE)
                    .find(syntax.text(node)) ?: continue
            val process = mutableListOf<String>()
            val terminal = mutableListOf<String>()
            var done = false
            match.groupValues[1].trim().split(Regex("\\s+")).forEach { word ->
                if (word == "|") done = true
                else {
                    val token = word.substringBefore('(')
                    if (token.isNotEmpty()) {
                        val parts = word.substringAfter('(', "").removeSuffix(")").split('/')
                        fun rule(value: String) =
                            if ('@' in value) "note" else if ('!' in value) "time" else "none"
                        styles[token] =
                            (styles[token] ?: KeywordStyle()).copy(
                                key = parts[0].takeWhile { it != '!' && it != '@' },
                                log = StateLog(rule(parts[0]), rule(parts.getOrElse(1) { "" })),
                            )
                        if (done) terminal += token else process += token
                    }
                }
            }
            if (terminal.isEmpty() && process.size > 1)
                terminal += process.removeAt(process.lastIndex)
            if (process.isNotEmpty() && terminal.isNotEmpty())
                sequences +=
                    WorkflowSequence(
                        "file-${sequences.size}",
                        process,
                        terminal,
                        process.first(),
                        terminal.first(),
                        process.first(),
                    )
        }
        return if (sequences.isEmpty()) base else Workflow(sequences, styles)
    }

    fun resolve(document: OrgDocument, item: OrgHeading): OrgHeading {
        if (document.text.sha256() == item.sourceHash) return document.heading(item.start)
        val candidates =
            document.headings.filter {
                it.sectionHash == item.sectionHash &&
                    it.title == item.title &&
                    it.ancestors == item.ancestors
            }
        require(candidates.size == 1) {
            "This item changed or is ambiguous. Reopen it before saving."
        }
        return candidates.single()
    }

    private fun editableBody(syntax: SyntaxDocument, start: Int, end: Int) =
        syntax.blocks.filter { node ->
            node.start >= start &&
                node.end <= end &&
                node.type in setOf("paragraph", "list", "quote_block") &&
                !syntax.text(node).trim().matches(Regex("^<[^>\\r\\n]+>(?:--<[^>\\r\\n]+>)?$"))
        }

    private fun check(document: OrgDocument, item: OrgHeading) {
        require(item.path == document.path && document.text.sha256() == item.sourceHash) {
            "This item changed. Reopen it before saving."
        }
        document.syntax.requireSafe(item.start, item.bodyEnd)
    }

    fun toggleCheckbox(document: OrgDocument, start: Int, checked: Boolean): String {
        val node = document.syntax.nodes.single { it.type == "checkbox" && it.start == start }
        document.syntax.requireSafe(node.start, node.end)
        return document.syntax.source.apply(
            listOf(SourcePatch(node.start + 1, node.start + 2, if (checked) "X" else " "))
        )
    }

    override fun changeItem(
        document: OrgDocument,
        item: OrgHeading,
        title: String,
        state: String,
        priority: String,
        tags: List<String>,
    ): String {
        check(document, item)
        require(title.isNotBlank() && '\n' !in title && '\r' !in title)
        require(state.isEmpty() || state in item.workflow.tokens)
        require(priority.isEmpty() || priority.matches(Regex("[A-Z]|[0-9]{1,2}")))
        require(tags.all { it.matches(Regex("[A-Za-z0-9_@#%]+")) })
        val patches = mutableListOf<SourcePatch>()
        if (title != item.title) patches += SourcePatch(item.titleStart, item.titleEnd, title)
        // Combine adjacent prefix fields so two insertions at one boundary have deterministic
        // ordering.
        if (state != item.state || priority != item.priority) {
            val stateText =
                if (state == item.state)
                    document.syntax.source.slice(item.stateStart, item.stateEnd)
                else if (state.isEmpty()) "" else "$state "
            val priorityText =
                if (priority == item.priority)
                    document.syntax.source.slice(item.priorityStart, item.priorityEnd)
                else if (priority.isEmpty()) "" else "[#$priority] "
            patches += SourcePatch(item.stateStart, item.priorityEnd, stateText + priorityText)
        }
        if (tags != item.tags)
            patches +=
                SourcePatch(
                    item.tagsStart,
                    item.tagsEnd,
                    if (tags.isEmpty()) ""
                    else (if (item.tags.isEmpty()) " " else "") + ":${tags.joinToString(":") }:",
                )
        return document.syntax.source.apply(patches)
    }

    fun setPlanning(
        document: OrgDocument,
        item: OrgHeading,
        keyword: String,
        date: LocalDateTime?,
        hasTime: Boolean,
        repeater: String = "",
        config: WorkspaceConfig = WorkspaceConfig.defaults(),
        note: String = "",
    ): String {
        check(document, item)
        require(keyword in listOf("SCHEDULED", "DEADLINE"))
        require(repeater.isEmpty() || OrgRepeater.parse(repeater) != null)
        val existing = item.stamps.firstOrNull { it.keyword == keyword }
        val nl = document.syntax.source.newline
        var result = document.text
        if (date == null && existing != null) {
            val entry =
                document.syntax.nodes.first {
                    it.type == "planning_entry" &&
                        existing.start >= it.start &&
                        existing.end <= it.end
                }
            result = document.syntax.source.apply(listOf(SourcePatch(entry.start, entry.end, "")))
        } else if (date != null) {
            val stamp =
                if (existing != null) existing.editing(date, hasTime, repeater)
                else
                    orgTimestamp(date, true, hasTime).let {
                        if (repeater.isEmpty()) it else it.dropLast(1) + " $repeater>"
                    }
            result =
                document.syntax.source.apply(
                    listOf(
                        if (existing != null) SourcePatch(existing.start, existing.end, stamp)
                        else
                            SourcePatch(
                                item.end,
                                item.end,
                                document.syntax.source.linePrefix(item.end) + "$keyword: $stamp$nl",
                            )
                    )
                )
        }
        val rule =
            if (keyword == "SCHEDULED") config.logging.reschedule else config.logging.redeadline
        if (result != document.text && rule != "none") {
            require(rule != "note" || note.isNotBlank()) { "A note is required" }
            if (existing != null) {
                val action =
                    if (keyword == "SCHEDULED")
                        if (date == null) "Not scheduled, was" else "Rescheduled from"
                    else if (date == null) "Removed deadline, was" else "New deadline from"
                val oldStamp = existing.raw.replace('<', '[').replace('>', ']')
                result =
                    addLog(
                        parse(document.path, result, config),
                        item.start,
                        "- $action \"$oldStamp\" on ${orgTimestamp(LocalDateTime.now(), false)}" +
                            if (rule == "note") logNote(note, nl) else "",
                        config.logging.drawer,
                    )
            }
        }
        return result
    }

    fun editPlanningSource(
        document: OrgDocument,
        node: OrgNode,
        replacement: String,
        config: WorkspaceConfig,
        note: String = "",
    ): String {
        document.syntax.requireSafe(node.start, node.end)
        var text =
            document.syntax.source.apply(listOf(SourcePatch(node.start, node.end, replacement)))
        val original = document.headings.lastOrNull { it.start <= node.start } ?: return text
        var updated = parse(document.path, text, config)
        val next = updated.heading(original.start)
        for ((keyword, rule) in
            listOf(
                "SCHEDULED" to config.logging.reschedule,
                "DEADLINE" to config.logging.redeadline,
            )) {
            val old = original.stamps.firstOrNull { it.keyword == keyword }
            val new = next.stamps.firstOrNull { it.keyword == keyword }
            if (old?.raw == new?.raw || rule == "none") continue
            require(rule != "note" || note.isNotBlank()) { "A note is required" }
            if (old == null) continue
            val action =
                if (keyword == "SCHEDULED")
                    if (new == null) "Not scheduled, was" else "Rescheduled from"
                else if (new == null) "Removed deadline, was" else "New deadline from"
            val stamp = old.raw.replace('<', '[').replace('>', ']')
            text =
                addLog(
                    updated,
                    original.start,
                    "- $action \"$stamp\" on ${orgTimestamp(LocalDateTime.now(), false)}" +
                        logNote(note, updated.syntax.source.newline),
                    config.logging.drawer,
                )
            updated = parse(document.path, text, config)
        }
        return text
    }

    fun setEvent(
        document: OrgDocument,
        item: OrgHeading,
        date: LocalDateTime?,
        hasTime: Boolean,
        repeater: String,
        duration: Int? = null,
    ): String {
        check(document, item)
        val existing = item.event
        var stamp = date?.let {
            existing?.editing(it, hasTime, repeater)
                ?: orgTimestamp(it, includesTime = hasTime).let { raw ->
                    if (repeater.isEmpty()) raw else raw.dropLast(1) + " $repeater>"
                }
        }
        if (
            stamp != null &&
                hasTime &&
                duration != null &&
                duration in 1..1439 &&
                !stamp.contains("--")
        ) {
            val end =
                date!!
                    .plusMinutes(duration.toLong())
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            stamp =
                stamp.replace(Regex("([0-9]{2}:[0-9]{2})(?:-[0-9]{2}:[0-9]{2})?")) {
                    it.groupValues[1] + "-$end"
                }
        }
        if (existing != null)
            return document.syntax.source.apply(
                listOf(SourcePatch(existing.start, existing.end, stamp ?: ""))
            )
        if (stamp == null) return document.text
        return document.syntax.source.apply(
            listOf(
                SourcePatch(
                    item.end,
                    item.end,
                    document.syntax.source.linePrefix(item.end) +
                        stamp +
                        document.syntax.source.newline,
                )
            )
        )
    }

    override fun complete(
        document: OrgDocument,
        item: OrgHeading,
        config: WorkspaceConfig,
        now: ZonedDateTime,
        note: String,
    ): String {
        val primary = item.agendaStamp
        val repeater = primary?.repeater?.takeUnless { item.done }
        if (repeater == null)
            return transition(document, item, item.workflow.toggle(item.state), config, now, note)
        check(document, item)
        val count =
            document.syntax.nodes.count {
                it.start >= item.end &&
                    it.end <= item.bodyEnd &&
                    it.type == "active_timestamp" &&
                    OrgStamp.parse(document.syntax.text(it), it.start, it.end)?.repeater != null
            }
        require(count <= 1) {
            "This item has multiple repeating timestamps. Update them together in the source editor."
        }
        val next = repeater.next(primary.date.atZone(now.zone), now).toLocalDateTime()
        var updated = document
        if (item.isTask) {
            var text =
                transition(
                    updated,
                    updated.heading(item.start),
                    item.workflow.toggle(item.state),
                    config,
                    now,
                    note,
                )
            updated = parse(item.path, text, config)
            text =
                transition(
                    updated,
                    updated.heading(item.start),
                    item.workflow.sequences.first { item.state in it.process + it.terminal }.reopen,
                    config,
                    now,
                    note,
                )
            updated = parse(item.path, text, config)
        }
        val current = updated.heading(item.start)
        val stamp = current.agendaStamp!!
        val text =
            if (item.isTask && stamp.keyword in listOf("SCHEDULED", "DEADLINE"))
                setPlanning(
                    updated,
                    current,
                    stamp.keyword!!,
                    next,
                    stamp.hasTime,
                    stamp.repeater!!.token,
                    config,
                    note,
                )
            else
                updated.syntax.source.apply(
                    listOf(SourcePatch(stamp.start, stamp.end, stamp.replacingDate(next)))
                )
        updated = parse(item.path, text, config)
        return setProperty(
            updated,
            updated.heading(item.start),
            "LAST_REPEAT",
            orgTimestamp(now.toLocalDateTime(), false),
        )
    }

    fun transition(
        document: OrgDocument,
        item: OrgHeading,
        nextState: String,
        config: WorkspaceConfig,
        now: ZonedDateTime = ZonedDateTime.now(),
        note: String = "",
    ): String {
        check(document, item)
        if (item.state == nextState) return document.text
        val enter = item.workflow.style(nextState).log.enter
        val leave = item.workflow.style(item.state).log.leave
        val isDone = item.workflow.terminal(nextState)
        require(
            listOf(enter, leave, if (isDone) config.logging.done else "none").none {
                it == "note"
            } || note.isNotBlank()
        ) {
            "A note is required"
        }
        var text = changeItem(document, item, state = nextState)
        var updated = parse(document.path, text, config)
        val current = updated.heading(item.start)
        if (isDone && (!item.done && config.logging.done != "none" || item.done)) {
            val closed = current.stamps.firstOrNull { it.keyword == "CLOSED" }
            text =
                updated.syntax.source.apply(
                    listOf(
                        if (closed != null)
                            SourcePatch(
                                closed.start,
                                closed.end,
                                orgTimestamp(now.toLocalDateTime(), false),
                            )
                        else
                            SourcePatch(
                                current.end,
                                current.end,
                                updated.syntax.source.linePrefix(current.end) +
                                    "CLOSED: ${orgTimestamp(now.toLocalDateTime(), false)}${updated.syntax.source.newline}",
                            )
                    )
                )
            updated = parse(document.path, text, config)
        } else if (!isDone && item.done) {
            val closed = current.stamps.firstOrNull { it.keyword == "CLOSED" }
            if (closed != null) {
                val entry =
                    updated.syntax.nodes.first {
                        it.type == "planning_entry" &&
                            closed.start >= it.start &&
                            closed.end <= it.end
                    }
                text = updated.syntax.source.apply(listOf(SourcePatch(entry.start, entry.end, "")))
                updated = parse(document.path, text, config)
            }
        }
        if (enter != "none" || leave != "none" || isDone && config.logging.done != "none") {
            text =
                addLog(
                    updated,
                    item.start,
                    "- State \"$nextState\" from \"${item.state}\" ${orgTimestamp(now.toLocalDateTime(), false)}" +
                        logNote(note, updated.syntax.source.newline),
                    config.logging.drawer,
                )
        }
        return text
    }

    private fun logNote(note: String, newline: String): String =
        if (note.isBlank()) ""
        else
            " \\\\" +
                newline +
                note.replace("\r\n", "\n").lines().joinToString(newline) {
                    "  " + if (it.trim().equals(":END:", true)) ",$it" else it
                }

    fun setProperty(document: OrgDocument, item: OrgHeading, name: String, value: String): String {
        check(document, item)
        require(
            name.matches(Regex("[A-Za-z0-9_@#%+.-]+")) && value.none { it == '\r' || it == '\n' }
        )
        val drawers =
            document.syntax.blocks.filter {
                it.start >= item.end && it.end <= item.bodyEnd && it.type == "property_drawer"
            }
        require(drawers.size <= 1) { "Multiple property drawers" }
        val nl = document.syntax.source.newline
        val drawer = drawers.firstOrNull()
        if (drawer != null) {
            val properties =
                document.syntax.children(drawer).filter {
                    it.type == "property" &&
                        document.syntax.text(it).trimStart().startsWith(":$name:", true)
                }
            require(properties.size <= 1) { "Duplicate property" }
            val property = properties.firstOrNull()
            if (property != null) {
                val content = document.syntax.text(property)
                val prefix = Regex("^[ \\t]*:[^:]+:[ \\t]*").find(content)!!.value
                val suffix = content.takeLastWhile { it in " \t\r\n" }
                return document.syntax.source.apply(
                    listOf(SourcePatch(property.start, property.end, prefix + value + suffix))
                )
            }
            val end = document.syntax.children(drawer).first { it.type == "property_drawer_end" }
            return document.syntax.source.apply(
                listOf(SourcePatch(end.start, end.start, ":$name: $value$nl"))
            )
        }
        val point =
            document.syntax.blocks
                .filter { it.start >= item.end && it.end <= item.bodyEnd }
                .takeWhile { it.type in listOf("planning", "blank_line") }
                .lastOrNull()
                ?.end ?: item.end
        return document.syntax.source.apply(
            listOf(
                SourcePatch(
                    point,
                    point,
                    document.syntax.source.linePrefix(point) +
                        ":PROPERTIES:$nl:$name: $value$nl:END:$nl",
                )
            )
        )
    }

    fun addLog(document: OrgDocument, headingStart: Int, line: String, drawer: String): String {
        val item = document.heading(headingStart)
        document.syntax.requireSafe(item.start, item.bodyEnd)
        val nl = document.syntax.source.newline
        val body = document.syntax.blocks.filter { it.start >= item.end && it.start < item.bodyEnd }
        val drawers = body.filter {
            it.type == "drawer" &&
                document.syntax.text(it).trimStart().startsWith(":$drawer:", true)
        }
        require(drawers.size <= 1) { "Multiple log drawers; edit the source to resolve ambiguity" }
        val existing = drawers.firstOrNull()
        if (drawer.isNotEmpty() && existing != null) {
            val point =
                existing.start +
                    document.syntax.text(existing).substringBefore('\n').utf8Bytes().size +
                    1
            return document.syntax.source.apply(
                listOf(
                    SourcePatch(point, point, document.syntax.source.linePrefix(point) + line + nl)
                )
            )
        }
        val point =
            body
                .takeWhile { it.type in listOf("planning", "property_drawer", "blank_line") }
                .lastOrNull()
                ?.end ?: item.end
        val content = if (drawer.isEmpty()) line + nl else ":$drawer:$nl$line$nl:END:$nl"
        return document.syntax.source.apply(
            listOf(SourcePatch(point, point, document.syntax.source.linePrefix(point) + content))
        )
    }

    fun moveSubtree(
        document: OrgDocument,
        item: OrgHeading,
        destination: OrgDocument,
        parent: OrgHeading?,
    ): Pair<String, String> {
        check(document, item)
        document.syntax.requireSafe(item.start, item.subtreeEnd)
        if (parent != null) check(destination, parent)
        val insertion = parent?.subtreeEnd ?: destination.syntax.source.bytes.size
        require(document.path != destination.path || insertion !in item.start..item.subtreeEnd) {
            "Cannot move a heading into itself"
        }
        val body = document.syntax.source.slice(item.start, item.subtreeEnd)
        val level = (parent?.level ?: 0) + 1
        val delta = level - item.level
        val moved =
            OrgParser().use { parser ->
                val syntax = parser.parse(body)
                syntax.source.apply(
                    syntax.blocks
                        .filter { it.type == "heading" }
                        .map {
                            val count = syntax.text(it).takeWhile { c -> c == '*' }.length
                            SourcePatch(it.start, it.start + count, "*".repeat(count + delta))
                        }
                )
            }
        val prefix = destination.syntax.source.linePrefix(insertion)
        val suffix = if (moved.endsWith('\n')) "" else destination.syntax.source.newline
        val insert = SourcePatch(insertion, insertion, prefix + moved + suffix)
        val remove = SourcePatch(item.start, item.subtreeEnd, "")
        return if (document.path == destination.path) {
            val text = document.syntax.source.apply(listOf(remove, insert))
            text to text
        } else
            document.syntax.source.apply(listOf(remove)) to
                destination.syntax.source.apply(listOf(insert))
    }

    fun replaceBody(document: OrgDocument, item: OrgHeading, note: String): String {
        check(document, item)
        val body = editableBody(document.syntax, item.end, item.bodyEnd)
        val newline = document.syntax.source.newline
        val replacement =
            note.replace("\r\n", "\n").replace("\n", newline).trimEnd().let {
                if (it.isEmpty()) "" else it + newline
            }
        val point = body.firstOrNull()?.start ?: item.bodyEnd
        val patches = body.mapIndexed { index, node ->
            SourcePatch(node.start, node.end, if (index == 0) replacement else "")
        }
        return document.syntax.source.apply(
            if (patches.isEmpty())
                listOf(
                    SourcePatch(
                        point,
                        point,
                        document.syntax.source.linePrefix(point) + replacement,
                    )
                )
            else patches
        )
    }

    @Synchronized
    override fun close() {
        cache.values.forEach { it.parser.close() }
        cache.clear()
    }
}
