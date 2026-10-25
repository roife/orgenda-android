package com.roifewu.orgenda

import android.app.Application
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.WebDavBackend
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebDavSafetyTest {
    private lateinit var server: MockWebServer
    private lateinit var backend: WebDavBackend

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        backend =
            WebDavBackend(server.url("/dav/").toString(), "user", "password", allowTestHttp = true)
    }

    @After
    fun close() {
        server.shutdown()
    }

    @Test
    fun createUsesPreconditionAndVerifiesSavedBytes() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setBody("中文😀").setHeader("ETag", "\"v1\""))
        backend.write("task.org", "中文😀".toByteArray(), null)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("*", put.getHeader("If-None-Match"))
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun staleWriteNeverFallsBackToUnconditionalPut() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(412))
        assertTrue(
            runCatching {
                backend.write(
                    "task.org",
                    "new".toByteArray(),
                    RemoteVersion("\"old\"", "old".sha256()),
                )
            }
                .exceptionOrNull() is StorageFailure.Conflict
        )
        assertEquals("\"old\"", server.takeRequest().getHeader("If-Match"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun moveProhibitsOverwritingExistingDestination() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        backend.move("a.org", "b.org", RemoteVersion("\"source\"", "content".sha256()))
        val move = server.takeRequest()
        assertEquals("F", move.getHeader("Overwrite"))
        assertEquals("\"source\"", move.getHeader("If-Match"))
    }

    @Test
    fun missingOptionalPropertiesDoNotHideVisibleFile() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/task.org</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat><d:prop><d:getetag/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response></d:multistatus>"""
                )
        )
        assertEquals(listOf("task.org"), backend.scan().entries.map { it.path })
    }

    @Test
    fun missingWorkspaceIsNotRecreatedDuringReconnect() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(runCatching { backend.probe() }.exceptionOrNull() is StorageFailure.Missing)
        assertEquals(1, server.requestCount)
        assertEquals("PROPFIND", server.takeRequest().method)
    }

    @Test
    fun credentialsCannotFollowRedirectOutsideWorkspace() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(307)
                .setHeader("Location", server.url("/outside/task.org"))
        )
        assertTrue(runCatching { backend.read("task.org") }.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun onlyNewConnectionCanCreateMissingRoot() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody(
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
                )
        )
        backend.ensureRootDuringSetup()
        assertEquals("PROPFIND", server.takeRequest().method)
        val create = server.takeRequest()
        assertEquals("MKCOL", create.method)
        assertEquals("/dav", create.path)
        assertEquals("PROPFIND", server.takeRequest().method)
    }

    private fun rootResponse() =
        MockResponse().setResponseCode(207).setBody(
            """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        )

    @Test
    fun connectionChecksRootOnceAndPreservesConditionalWriteChecks() = runBlocking {
        server.enqueue(rootResponse())
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setBody("first").setHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setBody("second").setHeader("ETag", "\"v2\""))
        server.enqueue(MockResponse().setResponseCode(204))

        backend.testConnection(createRoot = true)

        assertEquals(8, server.requestCount)
        val root = server.takeRequest()
        assertEquals("PROPFIND", root.method)
        assertEquals("0", root.getHeader("Depth"))
        assertEquals("*", server.takeRequest().getHeader("If-None-Match"))
        assertEquals("GET", server.takeRequest().method)
        assertEquals("*", server.takeRequest().getHeader("If-None-Match"))
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-Match"))
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-Match"))
        assertEquals("GET", server.takeRequest().method)
        val cleanup = server.takeRequest()
        assertEquals("DELETE", cleanup.method)
        assertEquals("\"v2\"", cleanup.getHeader("If-Match"))
    }

    @Test
    fun connectionRejectsFileAsWorkspaceRoot() = runBlocking {
        server.enqueue(rootResponse().setBody(
            """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        ))

        assertTrue(runCatching { backend.probe() }.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun connectionHasOneDeadlineAcrossSetupAndResponseBodies() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setHeadersDelay(400, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setResponseCode(201).setHeadersDelay(400, TimeUnit.MILLISECONDS))
        server.enqueue(rootResponse().setBodyDelay(800, TimeUnit.MILLISECONDS))

        val started = System.nanoTime()
        val failure = runCatching {
            backend.testConnection(createRoot = true, timeoutMillis = 1200)
        }.exceptionOrNull()
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue("Expected timeout, got $failure", failure is StorageFailure.Unavailable)
        assertTrue(failure!!.message!!.contains("timed out"))
        assertTrue("Connection took $elapsed ms", elapsed < 3000)
        assertEquals(3, server.requestCount)
        assertEquals("PROPFIND", server.takeRequest().method)
        assertEquals("MKCOL", server.takeRequest().method)
        assertEquals("PROPFIND", server.takeRequest().method)
    }

    @Test
    fun stalledConnectionStopsWithoutRetryAndDoesNotLimitLaterReads() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()
        val failure = runCatching {
            backend.testConnection(createRoot = false, timeoutMillis = 1000)
        }.exceptionOrNull()
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue(failure is StorageFailure.Unavailable)
        assertTrue("Connection took $elapsed ms", elapsed < 3000)
        assertEquals(1, server.requestCount)

        server.enqueue(MockResponse().setBody("later"))
        assertEquals("later", backend.read("task.org").bytes.toString(Charsets.UTF_8))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun redirectsCannotRestartConnectionDeadline() = runBlocking {
        repeat(3) {
            server.enqueue(
                MockResponse().setResponseCode(307)
                    .setHeader("Location", server.url("/dav/"))
                    .setHeadersDelay(500, TimeUnit.MILLISECONDS)
            )
        }
        val started = System.nanoTime()
        val failure = runCatching {
            backend.testConnection(createRoot = false, timeoutMillis = 1200)
        }.exceptionOrNull()
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue(failure is StorageFailure.Unavailable)
        assertTrue(failure!!.message!!.contains("timed out"))
        assertTrue("Connection took $elapsed ms", elapsed < 3000)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun cleanupCannotExtendConnectionDeadline() = runBlocking {
        server.enqueue(rootResponse())
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setBody("first").setHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setBody("second").setHeader("ETag", "\"v2\""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()

        backend.testConnection(createRoot = false, timeoutMillis = 1200)

        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("Connection took $elapsed ms", elapsed < 3000)
        assertEquals(8, server.requestCount)
        repeat(7) { server.takeRequest() }
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun connectionPreservesThrottlingDuringWriteChecks() = runBlocking {
        server.enqueue(rootResponse())
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setBody("first").setHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        server.enqueue(MockResponse().setResponseCode(204))

        val failure = runCatching { backend.probe() }.exceptionOrNull()

        assertTrue("Expected throttling, got $failure", failure is StorageFailure.Throttled)
        assertEquals(120, (failure as StorageFailure.Throttled).retrySeconds)
        assertEquals(5, server.requestCount)
    }
}
