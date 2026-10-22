package com.roifewu.orgenda.core

import java.net.URI
import java.net.URLDecoder
import java.nio.file.Paths

data class ImageReference(val target: String, val label: String, val start: Int, val end: Int) {
    fun candidates(document: OrgDocument, attachments: String): List<String> {
        val parent = document.path.substringBeforeLast('/', "")
        return when (scheme(target)) {
            "http",
            "https" -> listOf(network(target))
            "attachment" -> {
                val name = decode(target.substringAfter(':'))
                val first =
                    document.headings.firstOrNull()?.start ?: document.syntax.source.bytes.size
                val fileProperties =
                    document.syntax.blocks
                        .filter { it.type == "property_drawer" && it.start < first }
                        .flatMap { drawer ->
                            document.syntax
                                .children(drawer)
                                .filter { it.type == "property" }
                                .mapNotNull { property ->
                                    Regex(":([^:]+):[ \\t]*(.*)")
                                        .find(document.syntax.text(property))
                                        ?.let {
                                            it.groupValues[1].uppercase() to
                                                it.groupValues[2].trim()
                                        }
                                }
                        }
                        .toMap()
                val scopes =
                    listOf(fileProperties) +
                        document.headings
                            .filter { start >= it.start && start < it.subtreeEnd }
                            .map { it.properties }
                val directory =
                    scopes.asReversed().firstNotNullOfOrNull {
                        it["DIR"]?.takeIf(String::isNotEmpty)
                    }
                if (directory != null)
                    listOf(
                        resolve(
                            name,
                            resolve(
                                if (scheme(directory) == "file") local(directory) else directory,
                                parent,
                            ),
                        )
                    )
                else {
                    val id =
                        scopes.asReversed().firstNotNullOfOrNull {
                            it["ID"]?.takeIf(String::isNotEmpty)
                        } ?: error("Attachment needs an inherited DIR or ID property")
                    require(
                        id.length > 2 &&
                            id.none { it == '/' || it == '\\' || it.isISOControl() } &&
                            id.take(2) != ".." &&
                            id.drop(2) !in listOf(".", "..")
                    ) {
                        "Invalid attachment ID"
                    }
                    val folder = id.take(2) + "/" + id.drop(2)
                    listOf(
                            resolve(name, resolve(folder, attachments)),
                            resolve(name, resolve("data/$folder", parent)),
                        )
                        .distinct()
                }
            }
            null,
            "file" -> listOf(resolve(local(target), parent))
            else -> error("Unsupported image link")
        }
    }

    companion object {
        private val extensions =
            setOf(
                "png",
                "jpg",
                "jpeg",
                "gif",
                "webp",
                "heic",
                "heif",
                "tif",
                "tiff",
                "bmp",
                "avif",
                "svg",
            )

        fun from(syntax: SyntaxDocument, node: OrgNode): ImageReference? {
            if (node.type != "link") return null
            val match =
                Regex("\\[\\[([^]]+)](?:\\[([^]]*)])?]").matchEntire(syntax.text(node))
                    ?: return null
            val target = match.groupValues[1]
            val path =
                runCatching {
                    when (scheme(target)) {
                        "http",
                        "https" -> decode(URI(network(target)).rawPath)
                        "attachment" -> decode(target.substringAfter(':'))
                        null,
                        "file" -> local(target)
                        else -> return null
                    }
                }
                    .getOrNull() ?: return null
            if (path.substringAfterLast('.', "").lowercase() !in extensions) return null
            return ImageReference(
                target,
                match.groupValues[2].ifEmpty { path.substringAfterLast('/') },
                node.start,
                node.end,
            )
        }

        private fun scheme(value: String) =
            Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(value)?.groupValues?.get(1)?.lowercase()

        private fun decode(value: String): String {
            // URLDecoder otherwise changes literal '+' to a space, which is incorrect for Org
            // paths.
            val decoded = URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
            require(
                decoded.isNotEmpty() && decoded.none(Char::isISOControl) && '\uFFFD' !in decoded
            ) {
                "Invalid image path"
            }
            return decoded
        }

        fun network(value: String): String {
            decode(value)
            val escaped = buildString {
                value.codePoints().forEach { cp ->
                    val char = String(Character.toChars(cp))
                    if (cp in 33..126) append(char)
                    else char.utf8Bytes().forEach { append("%%%02X".format(it.toInt() and 255)) }
                }
            }
            val uri = URI(escaped)
            require(
                uri.scheme.lowercase() in listOf("http", "https") &&
                    uri.rawUserInfo == null &&
                    !uri.host.isNullOrEmpty() &&
                    (uri.port == -1 || uri.port in 1..65535)
            ) {
                "Invalid image URL"
            }
            return uri.toASCIIString()
        }

        private fun local(value: String): String {
            if (scheme(value) == null) return decode(value)
            val path = value.substringAfter(':')
            if (!path.startsWith("//")) return decode(path)
            val uri = URI(value)
            require(uri.host.isNullOrEmpty() || uri.host.equals("localhost", true))
            require(
                uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null
            )
            return decode(uri.rawPath)
        }

        private fun resolve(path: String, base: String): String {
            require(!path.startsWith('/') && !path.startsWith('~') && scheme(path) == null) {
                "Use a path inside the selected workspace"
            }
            return safePath(Paths.get(base).resolve(path).normalize().toString())
        }
    }
}
