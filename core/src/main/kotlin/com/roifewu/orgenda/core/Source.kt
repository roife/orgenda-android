package com.roifewu.orgenda.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

fun String.utf8Bytes(): ByteArray {
    val buffer =
        Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(this))
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}

fun String.sha256(): String = utf8Bytes().sha256()

fun ByteArray.strictText(): String =
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()

data class SourcePatch(val start: Int, val end: Int, val replacement: String)

class SourceText(val text: String) {
    val bytes = text.utf8Bytes()
    private val positions: IntArray by lazy {
        IntArray(text.length + 1).also { offsets ->
            var index = 0
            var byte = 0
            while (index < text.length) {
                offsets[index] = byte
                val cp = text.codePointAt(index)
                if (cp > 0xffff) offsets[index + 1] = byte
                index += Character.charCount(cp)
                byte +=
                    when {
                        cp < 0x80 -> 1
                        cp < 0x800 -> 2
                        cp < 0x10000 -> 3
                        else -> 4
                    }
            }
            offsets[text.length] = byte
        }
    }

    fun boundary(offset: Int) =
        offset in 0..bytes.size && (offset == bytes.size || bytes[offset].toInt() and 0xc0 != 0x80)

    fun slice(start: Int, end: Int): String {
        require(start <= end && boundary(start) && boundary(end)) { "Invalid UTF-8 source range" }
        return bytes.copyOfRange(start, end).strictText()
    }

    fun utf16(byte: Int): Int {
        require(boundary(byte))
        val index = positions.binarySearch(byte)
        return if (index < text.length && text[index].isLowSurrogate()) index - 1 else index
    }

    fun byteOffset(utf16: Int): Int {
        require(utf16 in 0..text.length)
        require(
            utf16 == 0 ||
                utf16 == text.length ||
                !(text[utf16 - 1].isHighSurrogate() && text[utf16].isLowSurrogate())
        )
        return positions[utf16]
    }

    fun apply(patches: List<SourcePatch>): String {
        val ordered = patches.sortedBy { it.start }
        var cursor = 0
        val result = java.io.ByteArrayOutputStream()
        ordered.forEach { p ->
            require(p.start >= cursor && p.end >= p.start && boundary(p.start) && boundary(p.end)) {
                "Overlapping or invalid source patches"
            }
            result.write(bytes, cursor, p.start - cursor)
            result.write(p.replacement.utf8Bytes())
            cursor = p.end
        }
        result.write(bytes, cursor, bytes.size - cursor)
        return result.toByteArray().strictText()
    }

    val newline
        get() = if (text.contains("\r\n")) "\r\n" else "\n"

    fun linePrefix(point: Int) = if (point > 0 && bytes[point - 1] != 10.toByte()) newline else ""
}

fun safePath(path: String, allowEmpty: Boolean = false): String {
    require(
        (allowEmpty && path.isEmpty()) ||
            (path.isNotEmpty() &&
                !path.startsWith("~") &&
                path.none {
                    it == '\u0000' || it == '\\' || it == ':' || it == '\r' || it == '\n'
                } &&
                path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
    ) {
        "Use a relative path inside the workspace: $path"
    }
    return path
}

fun foldedPath(path: String): String =
    java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFC)
        .lowercase(java.util.Locale.ROOT)

internal object NativeBridge {
    init {
        System.loadLibrary("orgenda")
    }

    external fun open(): Long

    external fun parse(handle: Long, bytes: ByteArray): IntArray

    external fun symbols(): Array<String>

    external fun close(handle: Long)
}

data class OrgNode(
    val type: String,
    val start: Int,
    val end: Int,
    val parent: Int,
    val error: Boolean,
)

data class SyntaxDocument(val source: SourceText, val nodes: List<OrgNode>) {
    private val indices = nodes.withIndex().associate { it.value to it.index }
    private val children =
        nodes.groupBy { it.parent }.mapValues { (_, values) -> values.sortedBy { it.start } }
    val blocks = children[0].orEmpty()

    fun text(node: OrgNode) = source.slice(node.start, node.end)

    fun children(node: OrgNode): List<OrgNode> = children[indices[node]].orEmpty()

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
        require(
            blocks.none {
                it.start < end &&
                    it.end > start &&
                    ((it.error && !isRecoveredParagraphIndent(it)) ||
                        it.type == "orphan_drawer_delimiter")
            }
        ) {
            "Repair unfinished Org blocks or drawers before structural editing"
        }
    }

    // The grammar can recover an indented paragraph as a horizontal-whitespace ERROR followed
    // by a valid paragraph. Those bytes are ordinary body indentation, preserved by structural
    // edits. Only this exact recovery is safe; unfinished blocks/drawers still reject edits.
    private fun isRecoveredParagraphIndent(node: OrgNode): Boolean =
        node.type == "ERROR" && node.end > node.start &&
            (node.start == 0 || source.bytes[node.start - 1] == '\n'.code.toByte()) &&
            source.slice(node.start, node.end).all { it == ' ' || it == '\t' } &&
            blocks.any { it.start == node.end && it.type == "paragraph" && !it.error }
}

class OrgParser : AutoCloseable {
    private var handle = NativeBridge.open()
    private val symbols = NativeBridge.symbols()

    @Synchronized
    fun parse(source: String): SyntaxDocument {
        check(handle != 0L)
        val text = SourceText(source)
        val records = NativeBridge.parse(handle, text.bytes)
        val nodes =
            records.asList().chunked(5).map { (type, start, end, parent, error) ->
                OrgNode(symbols.getOrElse(type) { "ERROR" }, start, end, parent, error != 0)
            }
        return SyntaxDocument(text, nodes)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) NativeBridge.close(handle)
        handle = 0
    }
}

/** Presentation grouping keeps original byte ranges and child nodes intact. */
fun SyntaxDocument.groupParagraphs(): SyntaxDocument {
    val grouped = nodes.toMutableList()
    for ((parent, siblings) in nodes.withIndex().groupBy { it.value.parent }) {
        if (
            parent != 0 &&
                nodes.getOrNull(parent)?.type !in
                    setOf("custom_block", "dynamic_block", "drawer", "quote_block", "center_block")
        )
            continue
        var run = mutableListOf<IndexedValue<OrgNode>>()
        fun flush() {
            if (run.isNotEmpty()) {
                val index = grouped.size
                grouped +=
                    OrgNode(
                        "paragraph_group",
                        run.first().value.start,
                        run.last().value.end,
                        parent,
                        false,
                    )
                run.forEach { grouped[it.index] = it.value.copy(parent = index) }
            }
            run = mutableListOf()
        }
        for (sibling in siblings) {
            if (
                sibling.value.type in setOf("paragraph", "quote_line") &&
                    text(sibling.value).isNotBlank()
            ) {
                if (run.isNotEmpty() && run.last().value.end != sibling.value.start) flush()
                run += sibling
            } else {
                flush()
                // The parser can emit standalone indentation as an ERROR node.
                // It has no visible content and must not take up a preview row.
                if (sibling.value.type != "blank_line" && text(sibling.value).isBlank()) {
                    grouped[sibling.index] = sibling.value.copy(type = "blank_line")
                }
            }
        }
        flush()
    }
    return SyntaxDocument(source, grouped)
}
