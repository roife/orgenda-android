package com.roifewu.orgenda.core

import java.nio.file.Paths

data class OrgLink(val path: String, val search: String?) {
    fun byteOffset(document: OrgDocument?, text: String = document?.text.orEmpty()): Int? =
        when {
            search == null -> null
            search.startsWith("#") ->
                document
                    ?.headings
                    ?.firstOrNull {
                        it.properties["CUSTOM_ID"] == search.drop(1) ||
                            it.properties["ID"] == search.drop(1)
                    }
                    ?.start
            search.startsWith("*") ->
                document?.headings?.firstOrNull { it.title == search.trimStart('*', ' ') }?.start
            search.toIntOrNull() != null -> {
                var offset = 0
                var remaining = (search.toInt() - 1).coerceAtLeast(0)
                while (remaining > 0 && offset < text.length) {
                    val end = text.indexOfAny(charArrayOf('\r', '\n'), offset)
                    offset =
                        if (end < 0) text.length
                        else if (text[end] == '\r' && text.getOrNull(end + 1) == '\n') end + 2
                        else end + 1
                    remaining--
                }
                SourceText(text).byteOffset(offset)
            }
            else ->
                document
                    ?.syntax
                    ?.nodes
                    ?.firstOrNull {
                        it.type in listOf("target", "radio_target") &&
                            document.syntax.text(it).trim('<', '>') == search
                    }
                    ?.start
                    ?: document?.text?.let { source ->
                        SearchText.range(source, search)?.let { SourceText(source).byteOffset(it.first) }
                    }
        }

    companion object {
        fun parse(origin: String, target: String): OrgLink {
            val parts = target.removePrefix("file:").split("::", limit = 2)
            val local = target.startsWith("#") || target.startsWith("*")
            val path =
                if (local) origin
                else
                    Paths.get(origin.substringBeforeLast('/', ""))
                        .resolve(parts[0])
                        .normalize()
                        .toString()
            return OrgLink(safePath(path), if (local) target else parts.getOrNull(1))
        }
    }
}
