package dev.alllexey.itmowidgets.backend.feature.reviews.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.*

class HttpReviewsApiClientTest {
    private data class Reply(val status: Int, val body: String = "", val etag: String? = null, val delayMillis: Long = 0)
    private data class Seen(val path: String, val userAgent: String?, val accept: String?, val ifNoneMatch: String?)

    private val replies = ConcurrentHashMap<String, Reply>()
    private val seen = CopyOnWriteArrayList<Seen>()
    private lateinit var executor: ExecutorService
    private lateinit var server: HttpServer
    private lateinit var client: HttpReviewsApiClient

    @BeforeEach fun start() {
        executor = Executors.newCachedThreadPool()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@HttpReviewsApiClientTest.executor
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val headers = exchange.requestHeaders
                seen.add(Seen(path, headers.getFirst("User-Agent"), headers.getFirst("Accept"), headers.getFirst("If-None-Match")))
                val reply = replies[path] ?: Reply(404)
                if (reply.delayMillis > 0) Thread.sleep(reply.delayMillis)
                reply.etag?.let { exchange.responseHeaders.add("ETag", it) }
                val bytes = reply.body.toByteArray()
                exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        client = HttpReviewsApiClient(
            ReviewsSyncConfig(
                enabled = true,
                baseUrl = URI.create("http://127.0.0.1:${server.address.port}"),
                requestDelay = Duration.ZERO,
                requestTimeout = Duration.ofMillis(200),
            ),
            jacksonObjectMapper(),
        )
    }

    @AfterEach fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    @Test fun `registry merges both name maps into unique ISU ids and sends the stored ETag only when present`() {
        replies["/registry"] = Reply(
            200,
            """
            {"original": {"Иванов И. И.": 100003, "Петров П. П.": 100001, "Локальный": 12},
             "normalized": {"иванов и и": 100003, "сидоров с с": 100002},
             "insights": {"ignored": true}, "extra": 1}
        """,
            etag = "\"v2\"",
        )

        assertEquals(RegistryResult.Changed("\"v2\"", listOf(100001L, 100002L, 100003L)), client.registry(null))
        assertEquals(Seen("/registry", HttpReviewsApiClient.USER_AGENT, "application/json", null), seen.single())

        replies["/registry"] = Reply(304, etag = "\"v2\"")
        assertEquals(RegistryResult.NotModified, client.registry("\"v2\""))
        assertEquals("\"v2\"", seen.last().ifNoneMatch)
        assertEquals(HttpReviewsApiClient.USER_AGENT, seen.last().userAgent)
    }

    @Test fun `teacher comments are trimmed without NUL characters and blank texts and non web links are dropped`() {
        replies["/teacher/100123"] = Reply(
            200,
            """
            {"id": 100123, "name": "  Иванов Иван  ", "comments": [
              {"id": 1, "date": " 12:18 25.01.2025 ", "text": "  Хороший\u0000 преподаватель  ",
               "subject": {"title": " Математика "}, "source": {"title": " Канал ", "link": " https://t.me/example/1 "}},
              {"id": 2, "date": "", "text": "   ", "subject": {"title": "Физика"}, "source": {"title": "Канал"}},
              {"id": 3, "text": "Без даты", "subject": {"title": "  "}, "source": {"title": "Таблица", "link": "javascript:alert(1)"}},
              {"id": 4, "date": "до 2024", "text": "Старый", "source": {"link": "ftp://example.org/file"}}
            ]}
        """,
        )

        assertEquals(
            ReviewsTeacher(
                100123,
                "Иванов Иван",
                listOf(
                    ReviewsComment(1, "12:18 25.01.2025", "Хороший преподаватель", "Математика", "Канал", "https://t.me/example/1"),
                    ReviewsComment(3, "", "Без даты", null, "Таблица", null),
                    ReviewsComment(4, "до 2024", "Старый", null, null, null),
                ),
            ),
            client.teacher(100123),
        )
        assertEquals(Seen("/teacher/100123", HttpReviewsApiClient.USER_AGENT, "application/json", null), seen.single())

        replies["/teacher/100124"] = Reply(200, """{"id": 100124, "name": " ", "comments": []}""")
        assertEquals(ReviewsTeacher(100124, "#100124", emptyList()), client.teacher(100124))
    }

    @Test fun `an unknown teacher is null`() {
        assertNull(client.teacher(100404))
    }

    @Test fun `server errors and rate limits fail with the HTTP status`() {
        for (status in listOf(500, 429)) {
            replies["/teacher/100123"] = Reply(status, "synthetic upstream content")
            val failure = assertFailsWith<ReviewsSyncFailure> { client.teacher(100123) }
            assertEquals(ReviewSyncErrorCategory.HTTP, failure.category)
            assertEquals(status, failure.status)
            assertEquals("HTTP $status /teacher/100123", failure.summary())
        }
        replies["/registry"] = Reply(503)
        assertEquals("HTTP 503 /registry", assertFailsWith<ReviewsSyncFailure> { client.registry(null) }.summary())
    }

    @Test fun `malformed bodies foreign teachers comments without id and an empty registry are mapping failures`() {
        replies["/registry"] = Reply(200, "{not json")
        assertEquals("MAPPING /registry", assertFailsWith<ReviewsSyncFailure> { client.registry(null) }.summary())
        for (empty in listOf("""{"original": {}, "normalized": {}}""", """{"original": {"Локальный": 12}}""", "{}")) {
            replies["/registry"] = Reply(200, empty)
            assertEquals("MAPPING /registry empty", assertFailsWith<ReviewsSyncFailure> { client.registry(null) }.summary(), empty)
        }
        for (body in listOf(
            """{"id": 100999, "name": "Другой", "comments": []}""",
            """{"id": 100123, "name": "Иванов", "comments": [{"text": "Без id"}]}""",
            """{"id": 100123, "name": "Иванов"}""",
            "[]",
        )) {
            replies["/teacher/100123"] = Reply(200, body)
            val failure = assertFailsWith<ReviewsSyncFailure> { client.teacher(100123) }
            assertEquals(ReviewSyncErrorCategory.MAPPING, failure.category, body)
            assertEquals("MAPPING /teacher/100123", failure.summary())
        }
    }

    @Test fun `a response slower than the request timeout is a network failure`() {
        replies["/teacher/100123"] = Reply(200, """{"id": 100123, "name": "Иванов", "comments": []}""", delayMillis = 1_000)
        val failure = assertFailsWith<ReviewsSyncFailure> { client.teacher(100123) }
        assertEquals(ReviewSyncErrorCategory.NETWORK, failure.category)
        assertEquals("NETWORK /teacher/100123", failure.summary())
    }

    @Test fun `network calls cannot run inside an active transaction`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertFailsWith<IllegalStateException> { client.registry(null) }
            assertFailsWith<IllegalStateException> { client.teacher(100123) }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        assertTrue(seen.isEmpty())
    }
}
