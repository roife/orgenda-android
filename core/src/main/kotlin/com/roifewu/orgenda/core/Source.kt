package com.roifewu.orgenda.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
fun String.utf8Bytes(): ByteArray {
    val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(this))
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}
fun String.sha256(): String = utf8Bytes().sha256()
fun ByteArray.strictText(): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(this)).toString()

data class SourcePatch(val start: Int, val end: Int, val replacement: String)

class SourceText(val text: String) {
    val bytes = text.utf8Bytes()
    private val positions: IntArray by lazy {
        IntArray(text.length + 1).also { offsets ->
            var index = 0; var byte = 0
            while (index < text.length) {
                offsets[index] = byte
                val cp = text.codePointAt(index)
                if (cp > 0xffff) offsets[index + 1] = byte
                index += Character.charCount(cp)
                byte += when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
            }
            offsets[text.length] = byte
        }
    }
    fun boundary(offset: Int) = offset in 0..bytes.size && (offset == bytes.size || bytes[offset].toInt() and 0xc0 != 0x80)
    fun slice(start: Int, end: Int): String {
        require(start <= end && boundary(start) && boundary(end)) { "Invalid UTF-8 source range" }
        return bytes.copyOfRange(start, end).strictText()
    }
    fun utf16(byte: Int): Int {
        require(boundary(byte))
        var left = 0; var right = positions.lastIndex
        while (left < right) { val mid = (left + right) ushr 1; if (positions[mid] < byte) left = mid + 1 else right = mid }
        return left
    }
    fun byteOffset(utf16: Int): Int {
        require(utf16 in 0..text.length)
        require(utf16 == 0 || utf16 == text.length || !(text[utf16 - 1].isHighSurrogate() && text[utf16].isLowSurrogate()))
        return positions[utf16]
    }
    fun apply(patches: List<SourcePatch>): String {
        val ordered = patches.sortedBy { it.start }
        var cursor = 0
        val result = java.io.ByteArrayOutputStream()
        ordered.forEach { p ->
            require(p.start >= cursor && p.end >= p.start && boundary(p.start) && boundary(p.end)) { "Overlapping or invalid source patches" }
            result.write(bytes, cursor, p.start - cursor)
            result.write(p.replacement.utf8Bytes())
            cursor = p.end
        }
        result.write(bytes, cursor, bytes.size - cursor)
        return result.toByteArray().strictText()
    }
    val newline get() = if (text.contains("\r\n")) "\r\n" else "\n"
    fun linePrefix(point: Int) = if (point > 0 && bytes[point - 1] != 10.toByte()) newline else ""
}

fun safePath(path: String, allowEmpty: Boolean = false): String {
    require((allowEmpty && path.isEmpty()) || (path.isNotEmpty() && !path.startsWith("~") &&
        path.none { it == '\u0000' || it == '\\' || it == ':' || it == '\r' || it == '\n' } &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })) { "Use a relative path inside the workspace: $path" }
    return path
}

fun foldedPath(path: String): String = java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)

internal object NativeBridge {
    init { System.loadLibrary("orgenda") }
    external fun open(): Long
    external fun parse(handle: Long, bytes: ByteArray): IntArray
    external fun symbols(): Array<String>
    external fun close(handle: Long)
}

data class OrgNode(val type: String, val start: Int, val end: Int, val parent: Int, val error: Boolean)
data class SyntaxDocument(val source: SourceText, val nodes: List<OrgNode>) {
    private val indices = nodes.withIndex().associate { it.value to it.index }
    private val children = nodes.groupBy { it.parent }
    val blocks = children[0].orEmpty().sortedBy { it.start }
    fun text(node: OrgNode) = source.slice(node.start, node.end)
    fun children(node: OrgNode): List<OrgNode> {
        return children[indices[node]].orEmpty()
    }
    fun descendants(node: OrgNode): List<OrgNode> {
        val result = mutableListOf<OrgNode>()
        val pending = ArrayDeque(children(node).asReversed())
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            result += next
            pending.addAll(children(next).asReversed())
        }
        return result
    }
    fun requireSafe(start: Int, end: Int) {
        require(blocks.none { it.start < end && it.end > start && (it.error || it.type == "orphan_drawer_delimiter") }) {
            "Repair unfinished Org blocks or drawers before structural editing"
        }
    }
}

class OrgParser : AutoCloseable {
    private var handle = NativeBridge.open()
    private val symbols = NativeBridge.symbols()
    @Synchronized fun parse(source: String): SyntaxDocument {
        check(handle != 0L)
        val text = SourceText(source)
        val records = NativeBridge.parse(handle, text.bytes)
        val nodes = records.asList().chunked(5).map { (type, start, end, parent, error) ->
            OrgNode(symbols.getOrElse(type) { "ERROR" }, start, end, parent, error != 0)
        }
        return SyntaxDocument(text, nodes)
    }
    @Synchronized override fun close() { if (handle != 0L) NativeBridge.close(handle); handle = 0 }
}

/** Presentation grouping keeps original byte ranges and child nodes intact. */
fun SyntaxDocument.groupParagraphs(): SyntaxDocument {
    val grouped = nodes.toMutableList()
    for ((parent, siblings) in nodes.withIndex().groupBy { it.value.parent }) {
        if (parent != 0) continue
        var run = mutableListOf<IndexedValue<OrgNode>>()
        fun flush() {
            if (run.size > 1) {
                val index = grouped.size
                grouped += OrgNode("paragraph_group", run.first().value.start, run.last().value.end, parent, false)
                run.forEach { grouped[it.index] = it.value.copy(parent = index) }
            }
            run = mutableListOf()
        }
        for (sibling in siblings) {
            if (sibling.value.type == "paragraph" && text(sibling.value).isNotBlank()) {
                if (run.isNotEmpty() && run.last().value.end != sibling.value.start) flush()
                run += sibling
            } else flush()
        }
        flush()
    }
    return SyntaxDocument(source, grouped)
}
