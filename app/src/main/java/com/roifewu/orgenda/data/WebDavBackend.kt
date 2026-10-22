package com.roifewu.orgenda.data

import android.util.Xml
import com.roifewu.orgenda.core.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser

class WebDavBackend(
    address: String,
    username: String,
    password: String,
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 4
                    maxRequestsPerHost = 4
                }
            )
            .build(),
    allowTestHttp: Boolean = false,
) : StorageBackend {
    private val root =
        address.trimEnd('/').plus('/').toHttpUrl().also {
            require(
                (it.isHttps || allowTestHttp && it.host in listOf("localhost", "127.0.0.1")) &&
                    it.query == null &&
                    it.fragment == null &&
                    it.username.isEmpty() &&
                    it.password.isEmpty()
            ) {
                "Use an HTTPS WebDAV address without query or credentials"
            }
        }
    private val authorization = okhttp3.Credentials.basic(username, password)
    private val nutstore = root.host == "dav.jianguoyun.com"

    private fun url(path: String): HttpUrl {
        safePath(path, allowEmpty = true)
        return root.newBuilder().addPathSegments(path).build()
    }

    private fun version(revision: String?): Boolean =
        revision != null &&
            !revision.startsWith("W/") &&
            (nutstore || revision.startsWith('"') && revision.endsWith('"'))

    private fun response(request: Request): Response {
        var current = request
        repeat(5) {
            val response = client.newCall(current).execute()
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                val next = response.header("Location")?.let { current.url.resolve(it) }
                val code = response.code
                response.close()
                require(
                    next != null &&
                        next.scheme == root.scheme &&
                        next.host == root.host &&
                        next.port == root.port &&
                        next.encodedPath.startsWith(root.encodedPath) &&
                        next.username.isEmpty() &&
                        next.password.isEmpty() &&
                        next.query == null
                ) {
                    "Unsafe WebDAV redirect"
                }
                require(
                    current.method in listOf("GET", "HEAD", "PROPFIND") || code in listOf(307, 308)
                ) {
                    "Redirect cannot change a write method"
                }
                current = current.newBuilder().url(next).build()
            } else return response
        }
        throw StorageFailure.Unavailable("Too many redirects")
    }

    private fun request(
        method: String,
        target: HttpUrl,
        bytes: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val body =
            if (bytes != null || method in listOf("PUT", "PROPFIND", "MKCOL"))
                (bytes ?: ByteArray(0)).toRequestBody("application/octet-stream".toMediaType())
            else null
        return response(
            Request.Builder()
                .url(target)
                .header("Authorization", authorization)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .method(method, body)
                .build()
        )
    }

    private fun checked(response: Response, path: String) {
        when (response.code) {
            401,
            403 -> throw StorageFailure.Authentication()
            404 -> throw StorageFailure.Missing(path)
            409,
            412 -> throw StorageFailure.Conflict(path)
            429,
            503 -> {
                val value = response.header("Retry-After")
                val seconds =
                    value?.toLongOrNull()
                        ?: runCatching {
                                java.time.Duration.between(
                                        java.time.Instant.now(),
                                        java.time.ZonedDateTime.parse(
                                                value,
                                                java.time.format.DateTimeFormatter
                                                    .RFC_1123_DATE_TIME,
                                            )
                                            .toInstant(),
                                    )
                                    .seconds
                            }
                            .getOrDefault(60)
                throw StorageFailure.Throttled(seconds.coerceIn(1, 86400))
            }
        }
        if (!response.isSuccessful) throw StorageFailure.Unavailable("WebDAV HTTP ${response.code}")
    }

    private fun bytes(response: Response, limit: Int): ByteArray {
        val body = response.body ?: throw StorageFailure.Unavailable("Empty response")
        require(body.contentLength() <= limit) { "Download exceeds size limit" }
        val bytes = body.byteStream().use { it.readNBytes(limit + 1) }
        require(bytes.size <= limit) { "Download exceeds size limit" }
        return bytes
    }

    private fun list(path: String): List<RemoteEntry> {
        val xml =
            """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getetag/><d:getcontentlength/></d:prop></d:propfind>"""
                .toByteArray()
        return request(
                "PROPFIND",
                url(path),
                xml,
                mapOf("Depth" to "1", "Content-Type" to "application/xml; charset=utf-8"),
            )
            .use { response ->
                checked(response, path)
                val parser =
                    Xml.newPullParser().apply {
                        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                        setInput(bytes(response, 16 * 1024 * 1024).inputStream(), "UTF-8")
                    }
                val entries = mutableListOf<RemoteEntry>()
                var href = ""
                var etag: String? = null
                var size: Long? = null
                var directory = false
                var responseStatus = ""
                var resourceType = false
                var inPropstat = false
                var propStatus = ""
                var propEtag: String? = null
                var propSize: Long? = null
                var propDirectory = false
                var propResource = false
                var responses = 0
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG)
                        when (parser.name) {
                            "response" -> {
                                href = ""
                                etag = null
                                size = null
                                directory = false
                                responseStatus = ""
                                resourceType = false
                                responses++
                            }
                            "propstat" -> {
                                inPropstat = true
                                propStatus = ""
                                propEtag = null
                                propSize = null
                                propDirectory = false
                                propResource = false
                            }
                            "href" -> href = parser.nextText()
                            "resourcetype" -> propResource = true
                            "getetag" -> propEtag = parser.nextText().ifBlank { null }
                            "getcontentlength" -> propSize = parser.nextText().toLongOrNull()
                            "collection" -> propDirectory = true
                            "status" ->
                                if (inPropstat) propStatus = parser.nextText()
                                else responseStatus = parser.nextText()
                        }
                    if (parser.eventType == XmlPullParser.END_TAG && parser.name == "propstat") {
                        if (propStatus.contains(" 200 ")) {
                            if (propResource) {
                                resourceType = true
                                directory = propDirectory
                            }
                            if (propEtag != null) etag = propEtag
                            if (propSize != null) size = propSize
                        }
                        inPropstat = false
                    }
                    if (parser.eventType == XmlPullParser.END_TAG && parser.name == "response") {
                        require(
                            href.isNotEmpty() &&
                                resourceType &&
                                (responseStatus.isEmpty() || responseStatus.contains(" 200 "))
                        ) {
                            "Incomplete WebDAV inventory"
                        }
                        val resolved =
                            root.resolve(href)
                                ?: throw StorageFailure.Unavailable("Invalid WebDAV path")
                        require(
                            resolved.host == root.host &&
                                resolved.port == root.port &&
                                resolved.scheme == root.scheme &&
                                resolved.encodedPath.startsWith(root.encodedPath)
                        ) {
                            "WebDAV path leaves the workspace"
                        }
                        val segments =
                            resolved.pathSegments.drop(root.pathSize - 1).dropLastWhile {
                                it.isEmpty()
                            }
                        require(segments.none { '/' in it || '\\' in it })
                        val relative = segments.joinToString("/")
                        safePath(relative, true)
                        if (relative != path)
                            entries += RemoteEntry(relative, relative, etag, directory, size)
                    }
                    parser.next()
                }
                require(responses > 0) { "Empty WebDAV inventory response" }
                entries
            }
    }

    override suspend fun scan(): RemoteInventory =
        withContext(Dispatchers.IO) {
            val entries = mutableListOf<RemoteEntry>()
            val unavailable = mutableMapOf<String, String>()
            val pending = ArrayDeque<String>()
            pending.add("")
            var requests = 0
            while (pending.isNotEmpty()) {
                val path = pending.removeFirst()
                try {
                    val children = list(path)
                    requests++
                    for (entry in children) {
                        require(entry.path.substringBeforeLast('/', "") == path) {
                            "Invalid Depth:1 inventory"
                        }
                        if (entries.any { it.path == entry.path })
                            throw StorageFailure.Unavailable("Duplicate remote path")
                        entries += entry
                        if (
                            entry.directory &&
                                (!entry.path.substringAfterLast('/').startsWith('.') ||
                                    entry.path == ".orgenda-trash" ||
                                    entry.path.startsWith(".orgenda-trash/"))
                        )
                            pending.add(entry.path)
                    }
                    require(entries.size <= 100000) { "Workspace is too large" }
                } catch (e: Exception) {
                    if (
                        path.isEmpty() ||
                            e is StorageFailure.Authentication ||
                            e is StorageFailure.Throttled
                    )
                        throw e
                    unavailable[path] = e.message ?: "Unavailable"
                }
            }
            RemoteInventory(entries, unavailable, if (nutstore) maxOf(300, requests * 6L) else 30)
        }

    override suspend fun read(path: String, limit: Int): RemoteContent =
        withContext(Dispatchers.IO) {
            request("GET", url(path)).use { response ->
                checked(response, path)
                RemoteContent(
                    RemoteEntry(
                        path,
                        path,
                        response.header("ETag"),
                        size = response.body?.contentLength(),
                    ),
                    bytes(response, limit),
                )
            }
        }

    private suspend fun parents(path: String) {
        val parts = path.split('/').dropLast(1)
        for (i in parts.indices) mkdir(parts.take(i + 1).joinToString("/"))
    }

    override suspend fun write(
        path: String,
        bytes: ByteArray,
        expected: RemoteVersion?,
    ): RemoteContent =
        withContext(Dispatchers.IO) {
            safePath(path)
            parents(path)
            if (expected != null && !version(expected.revision))
                throw StorageFailure.Unsupported(
                    "Server has no reliable version for conditional writes"
                )
            if (expected == null && nutstore) {
                val parent = path.substringBeforeLast('/', "")
                val temp =
                    (if (parent.isEmpty()) "" else "$parent/") +
                        ".orgenda-upload-${UUID.randomUUID()}"
                request("PUT", url(temp), bytes, mapOf("If-None-Match" to "*")).use {
                    checked(it, temp)
                }
                request(
                        "MOVE",
                        url(temp),
                        headers = mapOf("Destination" to url(path).toString(), "Overwrite" to "F"),
                    )
                    .use { checked(it, path) }
            } else {
                request(
                        "PUT",
                        url(path),
                        bytes,
                        if (expected == null) mapOf("If-None-Match" to "*")
                        else mapOf("If-Match" to expected.revision!!),
                    )
                    .use { checked(it, path) }
            }
            val saved = read(path)
            if (!saved.bytes.contentEquals(bytes)) throw StorageFailure.Conflict(path)
            saved
        }

    override suspend fun mkdir(path: String) =
        withContext(Dispatchers.IO) {
            request("MKCOL", url(path)).use { response ->
                if (response.code !in listOf(405, 301)) checked(response, path)
                else {
                    // Existing must be a collection, never assume a same-name file is a folder.
                    request(
                            "PROPFIND",
                            url(path),
                            """<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
                                .toByteArray(),
                            mapOf("Depth" to "0"),
                        )
                        .use {
                            checked(it, path)
                            require(
                                bytes(it, 65536).toString(Charsets.UTF_8).contains("collection")
                            ) {
                                "Destination is not a folder"
                            }
                        }
                }
            }
        }

    override suspend fun move(
        from: String,
        to: String,
        expected: RemoteVersion?,
        directory: Boolean,
    ) =
        withContext(Dispatchers.IO) {
            parents(to)
            val headers = mutableMapOf("Destination" to url(to).toString(), "Overwrite" to "F")
            if (!directory) {
                require(expected != null && version(expected.revision)) {
                    "A reliable source version is required"
                }
                headers["If-Match"] = expected.revision!!
            }
            request("MOVE", url(from), headers = headers).use { checked(it, from) }
        }

    suspend fun ensureRootDuringSetup() =
        withContext(Dispatchers.IO) {
            try {
                list("")
                return@withContext
            } catch (_: StorageFailure.Missing) {}
            var target = root.newBuilder().encodedPath("/").build()
            for (segment in root.encodedPathSegments.filter { it.isNotEmpty() }) {
                target = target.newBuilder().addEncodedPathSegment(segment).build()
                request("MKCOL", target).use { response ->
                    // Existing collection parents commonly respond with Method Not Allowed.
                    if (response.code != 405) checked(response, target.encodedPath)
                }
            }
            list("")
        }

    override suspend fun probe() =
        withContext(Dispatchers.IO) {
            list("") // Reconnection must never recreate a workspace deleted elsewhere.
            val path = ".orgenda-probe-${UUID.randomUUID()}"
            var current: RemoteContent? = null
            try {
                val first = write(path, "first".toByteArray(), null)
                current = first
                val collision = runCatching {
                    write(path, "collision".toByteArray(), null)
                }
                    .exceptionOrNull()
                require(collision is StorageFailure.Conflict) { "Server ignores create protection" }
                val second =
                    write(
                        path,
                        "second".toByteArray(),
                        RemoteVersion(first.entry.revision, first.hash),
                    )
                current = second
                val stale = runCatching {
                    write(
                        path,
                        "stale".toByteArray(),
                        RemoteVersion(first.entry.revision, first.hash),
                    )
                }
                    .exceptionOrNull()
                require(stale is StorageFailure.Conflict) {
                    "Server ignores stale-write protection"
                }
                require(read(path).hash == second.hash) { "Server modified protected content" }
            } finally {
                val revision = current?.entry?.revision
                if (version(revision))
                    runCatching {
                        request("DELETE", url(path), headers = mapOf("If-Match" to revision!!))
                            .close()
                    }
            }
        }
}
