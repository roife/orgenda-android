package com.roifewu.orgenda

import android.app.Application
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.WebDavBackend
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NutstoreWebDavTest {
    private lateinit var server: MockWebServer
    private lateinit var remote: NutstoreServer
    private lateinit var transport: OkHttpClient

    @Before
    fun setup() {
        remote = NutstoreServer()
        server = MockWebServer().apply {
            dispatcher = remote
            start()
        }
        transport = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                // Keep the real provider URL in the backend; route only the test transport locally.
                val request = chain.request()
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }
            .build()
    }

    @After
    fun close() {
        transport.connectionPool.evictAll()
        server.shutdown()
    }

    private fun backend(host: String = "dav.jianguoyun.com") =
        WebDavBackend("https://$host/dav/", "user", "password", client = transport)

    private fun requests() = List(server.requestCount) {
        server.takeRequest(1, TimeUnit.SECONDS) ?: error("Missing request")
    }

    @Test
    fun connectionUsesElevenRequestsIncludingTemporaryFileCleanup() = runBlocking {
        backend().testConnection(createRoot = true)

        val requests = requests()
        assertEquals(
            listOf("PROPFIND", "PUT", "MOVE", "GET", "PUT", "MOVE", "DELETE", "PUT", "PUT", "GET", "DELETE"),
            requests.map { it.method },
        )
        assertEquals("0", requests[0].getHeader("Depth"))
        val firstTemp = requests[1].path
        val secondTemp = requests[4].path
        assertTrue(firstTemp!!.startsWith("/dav/.orgenda-upload-"))
        assertTrue(secondTemp!!.startsWith("/dav/.orgenda-upload-"))
        assertNotEquals(firstTemp, secondTemp)
        assertEquals("*", requests[1].getHeader("If-None-Match"))
        assertEquals("*", requests[4].getHeader("If-None-Match"))
        val probe = requests[3].path
        for (index in listOf(2, 5)) {
            assertEquals("F", requests[index].getHeader("Overwrite"))
            assertEquals("https://dav.jianguoyun.com$probe", requests[index].getHeader("Destination"))
        }
        assertEquals(secondTemp, requests[6].path)
        assertNull(requests[6].getHeader("If-Match"))
        assertEquals("v1", requests[7].getHeader("If-Match"))
        assertEquals("v1", requests[8].getHeader("If-Match"))
        assertEquals("second", requests[7].body.readUtf8())
        assertEquals("stale", requests[8].body.readUtf8())
        assertEquals(probe, requests[9].path)
        assertEquals(probe, requests[10].path)
        assertEquals("v3", requests[10].getHeader("If-Match"))
        assertTrue(remote.files.isEmpty())
    }

    @Test
    fun ordinaryCreateStillReadsBackSavedBytes() = runBlocking {
        val saved = backend().write("task.org", "content".toByteArray(), null)

        assertEquals("content", saved.bytes.toString(Charsets.UTF_8))
        assertEquals("v1", saved.entry.revision)
        assertEquals(listOf("PUT", "MOVE", "GET"), requests().map { it.method })
        assertEquals(listOf("/dav/task.org"), remote.files.keys.toList())
    }

    @Test
    fun connectionRejectsServerThatOverwritesExistingDestination() = runBlocking {
        remote.fault = Fault.IGNORE_CREATE_PROTECTION

        val failure = runCatching { backend().probe() }.exceptionOrNull()

        assertTrue(failure!!.message!!.contains("create protection"))
        assertFalse(requests().any { it.body.clone().readUtf8() == "second" })
    }

    @Test
    fun connectionRejectsServerThatAcceptsStaleWrite() = runBlocking {
        remote.fault = Fault.IGNORE_STALE_PROTECTION

        val failure = runCatching { backend().probe() }.exceptionOrNull()

        assertTrue(failure!!.message!!.contains("stale-write protection"))
    }

    @Test
    fun connectionReadsContentAfterRejectedStaleWrite() = runBlocking {
        remote.fault = Fault.MODIFY_REJECTED_STALE_WRITE

        val failure = runCatching { backend().probe() }.exceptionOrNull()

        assertTrue("Expected changed content, got $failure", failure is StorageFailure.Conflict)
        assertEquals(2, requests().count { it.method == "GET" })
        assertTrue(remote.files.values.any { it.text == "stale" })
    }

    @Test
    fun connectionRejectsCreateThatMutatesDestinationDespiteReturning412() = runBlocking {
        remote.fault = Fault.MODIFY_REJECTED_CREATE

        val failure = runCatching { backend().probe() }.exceptionOrNull()

        assertTrue(failure is StorageFailure.Conflict)
        assertFalse(remote.files.keys.any { it.contains(".orgenda-upload-") })
    }

    @Test
    fun connectionRequires412ForStaleWrite() = runBlocking {
        remote.fault = Fault.STALE_WRITE_RETURNS_409

        assertTrue(runCatching { backend().probe() }.exceptionOrNull() is StorageFailure.Conflict)
    }

    @Test
    fun connectionRejectsMissingUpdatedVersion() = runBlocking {
        remote.fault = Fault.MISSING_UPDATED_VERSION

        val failure = runCatching { backend().probe() }.exceptionOrNull()

        assertTrue(failure is StorageFailure.Unsupported)
        assertEquals(
            listOf("v1"),
            requests().filter { it.method == "DELETE" && it.path!!.contains(".orgenda-probe-") }
                .map { it.getHeader("If-Match") },
        )
    }

    @Test
    fun connectionRejectsWeakUpdatedVersion() = runBlocking {
        remote.fault = Fault.WEAK_UPDATED_VERSION

        assertTrue(runCatching { backend().probe() }.exceptionOrNull() is StorageFailure.Unsupported)
    }

    @Test
    fun connectionRequiresVersionToChangeAfterUpdate() = runBlocking {
        remote.fault = Fault.UNCHANGED_UPDATED_VERSION

        val failure = runCatching { backend().probe() }.exceptionOrNull()

        assertTrue(failure!!.message!!.contains("did not change the version"))
    }

    @Test
    fun connectionRejectsMissingInitialVersionBeforeMoreWrites() = runBlocking {
        remote.fault = Fault.MISSING_INITIAL_VERSION

        assertTrue(runCatching { backend().probe() }.exceptionOrNull() is StorageFailure.Unsupported)
        assertEquals(listOf("PROPFIND", "PUT", "MOVE", "GET"), requests().map { it.method })
    }

    @Test
    fun failedMoveCleansOnlyItsOwnTemporaryFileWithoutMetadataLookup() = runBlocking {
        remote.fault = Fault.FAIL_MOVE

        val failure = runCatching { backend().write("task.org", "content".toByteArray(), null) }.exceptionOrNull()

        assertTrue(failure is StorageFailure.Unavailable)
        val requests = requests()
        assertEquals(listOf("PUT", "MOVE", "DELETE"), requests.map { it.method })
        assertEquals(requests[0].path, requests[2].path)
        assertNull(requests[2].getHeader("If-Match"))
        assertTrue(remote.files.isEmpty())
    }

    @Test
    fun failedPutAlsoCleansPrivateUploadAndPreservesOriginalError() = runBlocking {
        remote.fault = Fault.FAIL_TEMP_PUT
        remote.cleanupFailure = true

        val failure = runCatching { backend().write("task.org", "content".toByteArray(), null) }.exceptionOrNull()

        assertTrue(failure is StorageFailure.Unavailable)
        assertEquals("WebDAV HTTP 500", failure!!.message)
        val requests = requests()
        assertEquals(listOf("PUT", "DELETE"), requests.map { it.method })
        assertEquals(requests[0].path, requests[1].path)
    }

    @Test
    fun failedMoveCleanupSharesConnectionDeadline() = runBlocking {
        remote.fault = Fault.FAIL_MOVE
        remote.stallCleanup = true
        val started = System.nanoTime()

        val failure = runCatching {
            backend().testConnection(createRoot = false, timeoutMillis = 1200)
        }.exceptionOrNull()

        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals("WebDAV HTTP 500", failure!!.message)
        assertTrue("Connection took $elapsed ms", elapsed < 3000)
        assertEquals(listOf("PROPFIND", "PUT", "MOVE", "DELETE"), requests().map { it.method })
    }

    @Test
    fun unsafeVersionsAreRejectedBeforeWritesOrParentCreation() = runBlocking {
        val invalid = listOf(
            null, "", " ", "\"\"", "W/\"v1\"", "w/v1", "*", "v*", "\"*\"", "v1,v2",
            "\"v1,v2\"", "v1\rv2", "v1\nv2", "v1\tv2", "v1\u0000", "v1\u007f", "v1\u0085",
            "v1 v2", "\"v1", "v1\"", "\"v\"1\"",
        )
        for (host in listOf("dav.jianguoyun.com", "webdav.example")) {
            val backend = backend(host)
            for (revision in invalid) {
                val expected = RemoteVersion(revision, "old".sha256())
                assertTrue("Accepted version $revision on $host", runCatching {
                    backend.write("nested/task.org", "new".toByteArray(), expected)
                }.exceptionOrNull() is StorageFailure.Unsupported)
                assertTrue(runCatching {
                    backend.move("task.org", "nested/task.org", expected)
                }.exceptionOrNull() is StorageFailure.Unsupported)
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun bareVersionsAreAcceptedOnlyForExactNutstoreHost() = runBlocking {
        for (host in listOf("webdav.example", "jianguoyun.com", "sub.dav.jianguoyun.com", "dav.jianguoyun.com.example")) {
            assertTrue(runCatching {
                backend(host).write("task.org", "new".toByteArray(), RemoteVersion("v1", "old".sha256()))
            }.exceptionOrNull() is StorageFailure.Unsupported)
        }
        assertEquals(0, server.requestCount)

        val revision = remote.seed("/dav/task.org", "old")
        val saved = backend().write("task.org", "new".toByteArray(), RemoteVersion(revision, "old".sha256()))
        assertEquals("new", saved.bytes.toString(Charsets.UTF_8))
        assertEquals("v2", saved.entry.revision)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun quotedVersionsWorkForStandardWebDavAndNutstore() = runBlocking {
        remote.quotedVersions = true
        for (host in listOf("webdav.example", "dav.jianguoyun.com")) {
            val revision = remote.seed("/dav/task.org", "old")
            val saved = backend(host).write("task.org", "new".toByteArray(), RemoteVersion(revision, "old".sha256()))
            assertEquals("new", saved.bytes.toString(Charsets.UTF_8))
            assertTrue(saved.entry.revision!!.startsWith('"'))
        }
        assertEquals(4, server.requestCount)
    }

    private enum class Fault {
        NONE, IGNORE_CREATE_PROTECTION, IGNORE_STALE_PROTECTION, MODIFY_REJECTED_STALE_WRITE,
        MODIFY_REJECTED_CREATE, STALE_WRITE_RETURNS_409, MISSING_UPDATED_VERSION, WEAK_UPDATED_VERSION,
        UNCHANGED_UPDATED_VERSION, MISSING_INITIAL_VERSION, FAIL_MOVE, FAIL_TEMP_PUT,
    }

    private data class Stored(val text: String, val revision: String)

    private class NutstoreServer : Dispatcher() {
        val files = ConcurrentHashMap<String, Stored>()
        private val serial = AtomicInteger()
        var fault = Fault.NONE
        var quotedVersions = false
        var cleanupFailure = false
        var stallCleanup = false

        fun seed(path: String, text: String): String {
            val value = "v${serial.incrementAndGet()}"
            val revision = if (quotedVersions) "\"$value\"" else value
            files[path] = Stored(text, revision)
            return revision
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            return when (request.method) {
                "PROPFIND" -> MockResponse().setResponseCode(207).setBody(
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
                )
                "PUT" -> {
                    val text = request.body.clone().readUtf8()
                    val previous = files[path]
                    if (path.contains(".orgenda-upload-")) {
                        if (request.getHeader("If-None-Match") != "*") return code(400)
                        seed(path, text)
                        code(if (fault == Fault.FAIL_TEMP_PUT) 500 else 201)
                    } else if (text == "stale" && fault == Fault.UNCHANGED_UPDATED_VERSION) {
                        code(412)
                    } else if (previous == null || request.getHeader("If-Match") != previous.revision) {
                        when (fault) {
                            Fault.IGNORE_STALE_PROTECTION -> { seed(path, text); code(204) }
                            Fault.MODIFY_REJECTED_STALE_WRITE -> { seed(path, text); code(412) }
                            Fault.STALE_WRITE_RETURNS_409 -> code(409)
                            else -> code(412)
                        }
                    } else {
                        if (fault == Fault.UNCHANGED_UPDATED_VERSION) files[path] = previous.copy(text = text)
                        else seed(path, text)
                        code(204)
                    }
                }
                "MOVE" -> {
                    if (fault == Fault.FAIL_MOVE) return code(500)
                    if (request.getHeader("Overwrite") != "F") return code(400)
                    val destination = request.getHeader("Destination")!!.toHttpUrl().encodedPath
                    val source = files[path] ?: return code(404)
                    if (files.containsKey(destination) && fault != Fault.IGNORE_CREATE_PROTECTION) {
                        if (fault == Fault.MODIFY_REJECTED_CREATE) files[destination] = source
                        code(412)
                    } else {
                        files[destination] = source
                        files.remove(path)
                        code(201)
                    }
                }
                "GET" -> {
                    val value = files[path] ?: return code(404)
                    val revision = when {
                        value.text == "first" && fault == Fault.MISSING_INITIAL_VERSION -> null
                        value.text == "second" && fault == Fault.MISSING_UPDATED_VERSION -> null
                        value.text == "second" && fault == Fault.WEAK_UPDATED_VERSION -> "W/\"weak\""
                        else -> value.revision
                    }
                    MockResponse().setBody(value.text).apply {
                        if (revision != null) setHeader("ETag", revision)
                    }
                }
                "DELETE" -> {
                    if (stallCleanup) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    if (cleanupFailure) return code(403)
                    val value = files[path] ?: return code(404)
                    val expected = request.getHeader("If-Match")
                    if (expected != null && expected != value.revision) code(412)
                    else { files.remove(path); code(204) }
                }
                else -> code(405)
            }
        }

        private fun code(status: Int) = MockResponse().setResponseCode(status)
    }
}
