package dev.alllexey.itmowidgets.backend.platform.http

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.*

class OutboundHttpClientTest {
    private data class Reply(val status: Int, val body: String = "", val location: String? = null, val delayMillis: Long = 0)

    private val seen = CopyOnWriteArrayList<String>()
    private var route: (String) -> Reply = { Reply(200) }
    private lateinit var executor: ExecutorService
    private lateinit var server: HttpServer
    private lateinit var base: String

    @BeforeEach fun start() {
        executor = Executors.newCachedThreadPool()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@OutboundHttpClientTest.executor
            createContext("/") { exchange ->
                val target = exchange.requestURI.toString()
                seen.add(target)
                val reply = route(target)
                if (reply.delayMillis > 0) Thread.sleep(reply.delayMillis)
                reply.location?.let { exchange.responseHeaders.add("Location", it) }
                val bytes = reply.body.toByteArray()
                exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun client(
        redirects: RedirectPolicy = RedirectPolicy.Never,
        maxBodyBytes: Int = 1024,
        requestTimeout: Duration = Duration.ofSeconds(5),
    ) = OutboundHttpClient(OutboundHttpSettings(Duration.ofSeconds(2), requestTimeout, maxBodyBytes, redirects))

    private fun get(path: String, headers: Map<String, String> = emptyMap()) = OutboundRequest(URI.create(base + path), headers)

    @Test fun `a slow answer and a closed port are network failures`() {
        route = { Reply(200, "late", delayMillis = 1_000) }
        val slow = assertFailsWith<OutboundHttpFailure> { client(requestTimeout = Duration.ofMillis(200)).send(get("/slow")) }
        assertEquals(OutboundHttpError.NETWORK, slow.error)
        assertEquals("NETWORK GET $base/slow HttpTimeoutException", slow.message)
        assertNull(slow.cause, "JDK messages may quote the full URI")

        val closed = OutboundRequest(URI.create("http://127.0.0.1:${ServerSocket(0).use { it.localPort }}/closed"))
        assertEquals(OutboundHttpError.NETWORK, assertFailsWith<OutboundHttpFailure> { client().send(closed) }.error)
    }

    @Test fun `bodies up to the limit are read, longer ones are too large and unwanted ones are not read`() {
        route = { target -> if (target == "/exact") Reply(200, "x".repeat(16)) else Reply(500, "x".repeat(17)) }

        assertEquals("x".repeat(16), client(maxBodyBytes = 16).send(get("/exact")).body.bytesOrNull()?.decodeToString())
        val tooLarge = client(maxBodyBytes = 16).send(get("/long"))
        assertEquals(500, tooLarge.status)
        assertEquals(ResponseBody.TooLarge, tooLarge.body)
        assertNull(tooLarge.body.bytesOrNull())

        val statuses = mutableListOf<Int>()
        val unread = client().send(get("/long")) { status ->
            statuses += status
            status == 200
        }
        assertEquals(ResponseBody.NotRead, unread.body)
        assertEquals(listOf(500), statuses)
    }

    @Test fun `redirects are returned, followed by the JDK or followed up to the manual limit`() {
        route = { target ->
            when (target) {
                "/start" -> Reply(302, location = "next?step=2")
                "/next?step=2" -> Reply(303, location = "$base/done")
                "/done" -> Reply(200, "done")
                "/nowhere" -> Reply(302)
                else -> Reply(307, location = "/loop?n=${target.length}")
            }
        }

        val returned = client().send(get("/start"))
        assertEquals(302, returned.status)
        assertEquals("next?step=2", returned.headers.firstValue("Location").orElse(null))
        assertEquals(listOf("/start"), seen)

        for (policy in listOf(RedirectPolicy.Normal, RedirectPolicy.Manual(2))) {
            seen.clear()
            val followed = client(policy).send(get("/start"))
            assertEquals(200, followed.status, policy.toString())
            assertEquals(URI.create("$base/done"), followed.uri, policy.toString())
            assertEquals(listOf("/start", "/next?step=2", "/done"), seen, policy.toString())
        }

        seen.clear()
        val loop = assertFailsWith<OutboundHttpFailure> { client(RedirectPolicy.Manual(3)).send(get("/loop")) }
        assertEquals(OutboundHttpError.TOO_MANY_REDIRECTS, loop.error)
        assertEquals(4, seen.size, "The first request and three redirects")

        val missing = assertFailsWith<OutboundHttpFailure> { client(RedirectPolicy.Manual(3)).send(get("/nowhere")) }
        assertEquals(OutboundHttpError.BAD_REDIRECT, missing.error)

        val post = OutboundRequest(URI.create("$base/start"), method = "POST", body = ByteArray(1))
        assertFailsWith<IllegalArgumentException> { client(RedirectPolicy.Manual(3)).send(post) }
    }

    @Test fun `text forms carry neither query strings nor header values nor bodies`() {
        val secret = "synthetic-secret-value"
        route = { Reply(200, secret, delayMillis = 1_000) }
        val request = OutboundRequest(
            URI.create("$base/pls/apex/f?p=2143:1:$secret"),
            mapOf("x-goog-api-key" to secret, "Cookie" to "KEYCLOAK_IDENTITY=$secret"),
            method = "POST",
            body = secret.toByteArray(),
        )
        val failure = assertFailsWith<OutboundHttpFailure> { client(requestTimeout = Duration.ofMillis(200)).send(request) }

        route = { Reply(200, secret) }
        val response = client().send(get("/page?session=$secret", mapOf("Cookie" to secret)))

        val texts = listOf(failure.message.orEmpty(), failure.toString(), request.toString(), response.toString())
        texts.forEach { assertFalse(secret in it, it) }
        assertEquals(
            "OutboundRequest(POST http://127.0.0.1:${server.address.port}/pls/apex/f?<redacted>, " +
                "headers=[x-goog-api-key, Cookie], body=22 bytes)",
            request.toString(),
        )
        assertEquals("http://127.0.0.1:${server.address.port}/page?<redacted>", HttpRedaction.target(response.uri))
        assertEquals("https://example.test:8443/a", HttpRedaction.target(URI.create("https://user:$secret@example.test:8443/a#$secret")))
        assertEquals("https://example.test/plain", HttpRedaction.target(URI.create("https://example.test/plain")))
    }
}
