package com.roifewu.orgenda.core

import java.nio.file.Paths

data class ArchiveTarget(val path: String, val heading: String) {
    companion object {
        private val declaration =
            Regex("^#\\+(?:PROPERTY:[ \\t]+ARCHIVE|ARCHIVE:)[ \\t]*(.*)", RegexOption.IGNORE_CASE)
        private val headingPattern = Regex("\\*+[ \\t]+\\S[^\\r\\n]*")

        fun resolve(document: OrgDocument, item: OrgHeading, defaultRule: String): ArchiveTarget {
            require(document.text.sha256() == item.sourceHash)
            val fileRules =
                document.syntax.blocks
                    .filter {
                        it.type == "keyword" &&
                            it.start < (document.headings.firstOrNull()?.start ?: Int.MAX_VALUE)
                    }
                    .mapNotNull {
                        declaration.find(document.syntax.text(it))?.groupValues?.get(1)?.trim()
                    }
            require(fileRules.size <= 1) { "Multiple archive declarations" }
            var rule = fileRules.firstOrNull() ?: defaultRule
            document.headings
                .filter { it.start <= item.start && it.subtreeEnd > item.start }
                .forEach { ancestor -> ancestor.properties["ARCHIVE"]?.let { rule = it } }
            val parts = rule.split("::")
            require(parts.size == 2)
            val relative = parts[0].trim().replace("%s", item.path.substringAfterLast('/'))
            require(!relative.startsWith('/') && !relative.startsWith('~') && '%' !in relative)
            val path =
                Paths.get(item.path.substringBeforeLast('/', ""))
                    .resolve(relative)
                    .normalize()
                    .toString()
            safePath(path)
            require(path.endsWith(".org") || path.endsWith(".org_archive"))
            require(parts[1].isBlank() || parts[1].trim().matches(headingPattern))
            return ArchiveTarget(path, parts[1])
        }
    }
}
