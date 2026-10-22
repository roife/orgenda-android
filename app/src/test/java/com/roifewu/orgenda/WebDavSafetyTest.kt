package com.roifewu.orgenda

import android.app.Application
import com.roifewu.orgenda.core.*
import com.roifewu.orgenda.data.WebDavBackend
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
}
