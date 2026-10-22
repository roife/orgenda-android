@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.roifewu.orgenda.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

@Serializable data class WorkspaceConfig(
    val version: Int,
    val workflow: Workflow = Workflow(),
    val files: FileRules = FileRules(),
    val logging: Logging = Logging(),
    val reminders: ReminderRules = ReminderRules(),
    val capture: CaptureRules = CaptureRules(),
    val agenda: AgendaRules = AgendaRules()
) {
    fun validate(): WorkspaceConfig {
        require(version == 1) { "Use a configuration with version 1" }
        require(workflow.sequences.size == 1) { "Exactly one default workflow sequence is required" }
        fun unique(values: List<String>) { require(values.none { it.isEmpty() } && values.distinct().size == values.size) { "Empty or duplicate value" } }
        unique(workflow.sequences.map { it.id })
        unique(workflow.tokens)
        workflow.tokens.forEach { token -> require(token.none { it.isWhitespace() || it in "()|:" }) { "Invalid workflow keyword: $token" } }
        workflow.sequences.forEach { s ->
            require(s.process.isNotEmpty() && s.terminal.isNotEmpty())
            require(s.initial in s.process && s.reopen in s.process && s.complete in s.terminal) { "Invalid workflow default state" }
        }
        require(workflow.keywords.keys.all { it in workflow.tokens })
        unique(workflow.keywords.values.map { it.key }.filter { it.isNotEmpty() })
        workflow.keywords.values.forEach {
            shortcut(it.key); require(it.icon in icons && it.color in colors)
            require(it.log.enter in logRules && it.log.leave in logRules)
        }
        orgPath(files.inbox); configPath(files.attachments); configPath(files.journal)
        files.refile.forEach(::orgPath)
        (agenda.sources + agenda.excluded).forEach(::configPath)
        require(files.refileMaxLevel in 1..99)
        require(reminders.advanceMinutes in 0..1440 && reminders.repeatMinutes in 1..1440 && reminders.deadlineWarningDays in 0..365)
        require(logging.done in logRules && logging.reschedule in logRules && logging.redeadline in logRules)
        require(logging.drawer.isEmpty() || (logging.drawer.matches(Regex("[\\p{L}\\p{N}\\p{M}_-]+")) && logging.drawer.uppercase() !in listOf("END", "PROPERTIES")))
        val archive = files.archive.split("::")
        require(archive.size == 2) { "Archive rule needs file::heading" }
        val file = archive[0].replace("%s", "example.org").trim()
        configPath(file)
        require('%' !in file && (file.endsWith(".org") || file.endsWith(".org_archive")))
        require(archive[1].trim().let { it.isEmpty() || it.matches(Regex("\\*+[ \\t]+\\S[^\\r\\n]*")) })
        unique(capture.templates.map { it.id }); unique(capture.templates.map { it.key }.filter { it.isNotEmpty() })
        require(capture.templates.any { it.id == capture.defaultTemplate }) { "Missing default capture template" }
        capture.templates.forEach { t ->
            require(t.name.isNotBlank() && t.template.isNotBlank()); shortcut(t.key); orgPath(t.target.path)
            require(t.emptyLines in 0..10 && t.type in listOf("entry", "item", "checkitem", "plain"))
            require(t.target.type in listOf("file", "headline", "outline", "datetree"))
            require(t.target.outline.all { it.isNotEmpty() && '\r' !in it && '\n' !in it })
            require(t.target.type in listOf("file", "datetree") || t.target.outline.isNotEmpty())
            require(t.type != "entry" || t.template.startsWith("* "))
            CaptureEngine.expansions(t.template)
        }
        return this
    }
    companion object {
        val icons = listOf("default", "circle", "arrow", "hourglass", "pause", "moon", "exclamation", "flag", "star", "check", "cross")
        val colors = listOf("default", "gray", "red", "orange", "amber", "green", "cyan", "blue", "purple", "pink")
        val logRules = listOf("none", "time", "note")
        fun defaults() = WorkspaceConfig(1)
        fun classic() = Workflow(listOf(WorkflowSequence(process = listOf("TODO", "NEXT", "WAIT", "SOMEDAY", "URGENT"), terminal = listOf("DONE", "CANCELED"))), mapOf(
            "WAIT" to KeywordStyle(log = StateLog("note", "time")), "URGENT" to KeywordStyle(log = StateLog("time")),
            "DONE" to KeywordStyle(log = StateLog("time")), "CANCELED" to KeywordStyle(log = StateLog("note"))))
        fun orgPath(path: String) { safePath(path); require(path.endsWith(".org")) { "Choose a path ending in .org" } }
        fun configPath(path: String) {
            require(path.isNotEmpty() && !path.startsWith('/') && !path.startsWith('~') && path.split('/').none { it == ".." } && path.none { it in "\\:\r\n\u0000\u000b\u000c\u0085\u2028\u2029" }) { "Use a relative workspace path" }
        }
        fun shortcut(key: String) { require((key.isEmpty() || Regex("\\X").matches(key)) && key.none { it.isWhitespace() }) }
    }
}

@Serializable data class Workflow(val sequences: List<WorkflowSequence> = listOf(WorkflowSequence()), val keywords: Map<String, KeywordStyle> = emptyMap()) {
    val tokens get() = sequences.flatMap { it.process + it.terminal }
    fun terminal(token: String) = sequences.any { token in it.terminal }
    fun toggle(token: String): String { val s = sequences.firstOrNull { token in it.process + it.terminal } ?: sequences.first(); return if (terminal(token)) s.reopen else s.complete }
    fun style(token: String) = keywords[token] ?: KeywordStyle()
}
@Serializable data class WorkflowSequence(val id: String = "tasks", val process: List<String> = listOf("TODO"), val terminal: List<String> = listOf("DONE"), val initial: String = "TODO", val complete: String = "DONE", val reopen: String = "TODO")
@Serializable data class KeywordStyle(val label: String = "", val key: String = "", val icon: String = "default", val color: String = "default", val log: StateLog = StateLog())
@Serializable data class StateLog(val enter: String = "none", val leave: String = "none")
@Serializable data class Logging(val done: String = "none", val drawer: String = "LOGBOOK", val reschedule: String = "none", val redeadline: String = "none")
@Serializable data class FileRules(val inbox: String = "inbox.org", val attachments: String = ".attach", val journal: String = "journal", val archive: String = "%s_archive::* Archived", val refile: List<String> = emptyList(), val refileMaxLevel: Int = 3)
@Serializable data class ReminderRules(val advanceMinutes: Int = 15, val repeatMinutes: Int = 5, val deadlineWarningDays: Int = 14)
@Serializable data class CaptureRules(val defaultTemplate: String = "inbox", val templates: List<CaptureTemplate> = listOf(CaptureTemplate()))
@Serializable data class CaptureTemplate(val id: String = "inbox", val name: String = "Inbox task", val key: String = "t", val group: String = "", val type: String = "entry", val target: CaptureTarget = CaptureTarget(), val template: String = "* TODO %^{Title}\n%?", val prepend: Boolean = false, val emptyLines: Int = 1)
@Serializable data class CaptureTarget(val type: String = "file", val path: String = "inbox.org", val outline: List<String> = emptyList())
@Serializable data class AgendaRules(val sources: List<String> = emptyList(), val excluded: List<String> = emptyList()) {
    fun includes(path: String): Boolean = path.endsWith(".org") && path.split('/').none { it.startsWith('.') || it in listOf("archives", "Unsaved Edits") } &&
        (sources.isEmpty() || sources.any { matches(path, it) }) && excluded.none { matches(path, it) }
    private fun matches(path: String, pattern: String) = path == pattern || path.startsWith(pattern.trimEnd('/') + "/")
}

object ConfigurationCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    fun decode(source: String): WorkspaceConfig {
        val tree = json.parseToJsonElement(source)
        require(tree is JsonObject && tree["version"] is JsonPrimitive && tree["version"] != JsonNull) { "version is required" }
        val normalized = normalize(tree, WorkspaceConfig.serializer().descriptor)
        return json.decodeFromJsonElement(WorkspaceConfig.serializer(), normalized).validate()
    }
    fun encode(config: WorkspaceConfig): String {
        config.validate()
        fun sorted(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.toSortedMap().mapValues { sorted(it.value) })
            is JsonArray -> JsonArray(element.map(::sorted))
            else -> element
        }
        return json.encodeToString(JsonElement.serializer(), sorted(json.encodeToJsonElement(WorkspaceConfig.serializer(), config))) + "\n"
    }
    private fun normalize(value: JsonElement, descriptor: SerialDescriptor): JsonElement {
        if (value == JsonNull) return value
        return when (descriptor.kind) {
            StructureKind.CLASS -> {
                require(value is JsonObject) { "${descriptor.serialName}: expected object" }
                JsonObject(value.mapNotNull { (key, child) ->
                    val i = descriptor.getElementIndex(key)
                    if (i == CompositeDecoderUnknown) key to child
                    else if (child == JsonNull && descriptor.isElementOptional(i)) null
                    else key to normalize(child, descriptor.getElementDescriptor(i))
                }.toMap())
            }
            StructureKind.LIST -> { require(value is JsonArray); JsonArray(value.map { normalize(it, descriptor.getElementDescriptor(0)) }) }
            StructureKind.MAP -> { require(value is JsonObject); JsonObject(value.mapValues { normalize(it.value, descriptor.getElementDescriptor(1)) }) }
            PrimitiveKind.STRING -> { require(value is JsonPrimitive && value.isString) { "Expected a string" }; value }
            PrimitiveKind.INT, PrimitiveKind.LONG -> { require(value is JsonPrimitive && !value.isString && value.longOrNull != null) { "Expected a whole number" }; value }
            PrimitiveKind.BOOLEAN -> { require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) { "Expected a boolean" }; value }
            else -> value
        }
    }
    private const val CompositeDecoderUnknown = -3
}
