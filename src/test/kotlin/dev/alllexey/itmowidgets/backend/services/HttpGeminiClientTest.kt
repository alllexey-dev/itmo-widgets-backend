package dev.alllexey.itmowidgets.backend.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.alllexey.itmowidgets.backend.configs.AiSummaryConfig
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * A local `HttpServer` plays the proxy: for an `http://` target the JDK client sends the absolute URI to the proxy,
 * so the test sees the Gemini host without any network or DNS.
 */
class HttpGeminiClientTest {
    private data class Reply(val status: Int, val body: String = "", val delayMillis: Long = 0)
    private data class Seen(val method: String, val uri: URI, val apiKey: String?, val userAgent: String?, val contentType: String?,
        val accept: String?, val body: String)

    private val mapper = jacksonObjectMapper()
    private val seen = CopyOnWriteArrayList<Seen>()
    @Volatile private var reply = Reply(200, OK_BODY)
    private lateinit var executor: ExecutorService
    private var server: HttpServer? = null

    @BeforeEach fun start() {
        executor = Executors.newCachedThreadPool()
        server = proxy(0)
    }

    @AfterEach fun stop() {
        server?.stop(0)
        executor.shutdownNow()
    }

    @Test fun `the request goes through the proxy with the key only in its header`() {
        client().generate(KEY, request())

        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("generativelanguage.test", request.uri.host)
        assertEquals("/v1beta/models/$MODEL:generateContent", request.uri.path)
        assertNull(request.uri.query)
        assertFalse(KEY in request.uri.toString())
        assertEquals(KEY, request.apiKey)
        assertEquals("application/json", request.contentType)
        assertEquals("application/json", request.accept)
        assertEquals(HttpGeminiClient.USER_AGENT, request.userAgent)
    }

    @Test fun `the body carries the instruction one user part and the generation config`() {
        client().generate(KEY, request())

        val body = mapper.readTree(seen.single().body)
        assertEquals(SYSTEM, body.at("/systemInstruction/parts/0/text").asText())
        assertEquals(1, body["contents"].size())
        assertEquals("user", body.at("/contents/0/role").asText())
        assertEquals(1, body.at("/contents/0/parts").size())
        assertEquals(USER_TEXT, body.at("/contents/0/parts/0/text").asText())
        val generation = body["generationConfig"]
        assertEquals("application/json", generation["responseMimeType"].asText())
        assertEquals(SCHEMA, generation["responseSchema"])
        assertEquals(0.2, generation["temperature"].asDouble())
        assertEquals(2048, generation["maxOutputTokens"].asInt())
        assertFalse(generation.has("thinkingConfig"))

        client(thinkingBudget = 0).generate(KEY, request())
        assertEquals(0, mapper.readTree(seen.last().body).at("/generationConfig/thinkingConfig/thinkingBudget").asInt())
    }

    @Test fun `a successful answer drops thought parts and keeps the finish reason and token counts`() {
        reply = Reply(200, """
            {"candidates": [{"content": {"role": "model", "parts": [
                {"text": "internal reasoning", "thought": true}, {"text": "{\"a\":"}, {"text": "1}"}]},
              "finishReason": "STOP", "index": 0, "safetyRatings": []}],
             "usageMetadata": {"promptTokenCount": 1050, "candidatesTokenCount": 349, "thoughtsTokenCount": 12, "totalTokenCount": 1411},
             "modelVersion": "synthetic", "responseId": "synthetic"}
        """)

        assertEquals(GeminiResponse(null, listOf(GeminiCandidate("STOP", "{\"a\":1}")), 1050, 349, 12), client().generate(KEY, request()))
    }

    @Test fun `a blocked prompt has a block reason and no candidates`() {
        reply = Reply(200, """{"promptFeedback": {"blockReason": "SAFETY"}, "usageMetadata": {"promptTokenCount": 10}}""")

        assertEquals(GeminiResponse("SAFETY", emptyList(), 10, null, null), client().generate(KEY, request()))
    }

    @Test fun `error statuses map to bounded categories`() {
        for ((replied, expected) in listOf(
            Reply(429, error(429, "RESOURCE_EXHAUSTED", "RATE_LIMIT_EXCEEDED")) to "RATE_LIMITED 429",
            Reply(403, error(403, "PERMISSION_DENIED", "PERMISSION_DENIED")) to "AUTH 403",
            Reply(401, "") to "AUTH 401",
            Reply(400, error(400, "INVALID_ARGUMENT", "API_KEY_INVALID")) to "AUTH 400 API_KEY_INVALID",
            Reply(400, error(400, "FAILED_PRECONDITION", null)) to "LOCATION 400",
            Reply(400, error(400, "INVALID_ARGUMENT", "SOMETHING_ELSE")) to "HTTP 400",
            Reply(503, error(503, "UNAVAILABLE", null)) to "HTTP 503",
            Reply(500, "<html>synthetic upstream page</html>") to "HTTP 500",
        )) {
            reply = replied
            val failure = assertFailsWith<GeminiFailure> { client().generate(KEY, request()) }
            assertEquals(expected, failure.summary(), replied.toString())
            assertEquals("Gemini request failed", failure.message)
        }
    }

    @Test fun `a slow answer and a closed proxy port are network failures`() {
        reply = Reply(200, OK_BODY, delayMillis = 1_000)
        val slow = assertFailsWith<GeminiFailure> { client().generate(KEY, request()) }
        assertEquals(GeminiErrorCategory.NETWORK, slow.category)
        assertEquals("NETWORK", slow.summary())

        val closed = assertFailsWith<GeminiFailure> { client(proxyPort = freePort()).generate(KEY, request()) }
        assertEquals(GeminiErrorCategory.NETWORK, closed.category)
    }

    @Test fun `an oversized or unreadable successful answer is a mapping failure`() {
        for (body in listOf("x".repeat(1024 * 1024 + 1), "{not json", "[]", """{"candidates": {}}""")) {
            reply = Reply(200, body)
            val failure = assertFailsWith<GeminiFailure> { client().generate(KEY, request()) }
            assertEquals("MAPPING", failure.summary(), body.take(20))
        }
    }

    @Test fun `the proxy address is resolved on connection, so a proxy started later works`() {
        val port = freePort()
        val client = client(proxyPort = port)
        val late = proxy(port)
        try {
            assertEquals("STOP", client.generate(KEY, request()).candidates.single().finishReason)
        } finally {
            late.stop(0)
        }
    }

    @Test fun `no message or text carries the key the prompt or the answer`() {
        val secretAnswer = "synthetic-answer-body"
        val failures = listOf(
            Reply(500, secretAnswer), Reply(400, error(400, "INVALID_ARGUMENT", "API_KEY_INVALID")), Reply(200, "{$secretAnswer"),
        ).map { replied ->
            reply = replied
            assertFailsWith<GeminiFailure> { client().generate(KEY, request()) }
        }
        reply = Reply(200, """{"candidates": [{"content": {"parts": [{"text": "$secretAnswer"}]}, "finishReason": "STOP"}]}""")
        val response = client().generate(KEY, request())
        val texts = failures.flatMap { listOf(it.message, it.summary(), it.toString()) } +
            listOf(request().toString(), response.toString(), config().copy(apiKey = KEY).toString())
        for (text in texts) {
            assertFalse(KEY in text.orEmpty(), text)
            assertFalse(USER_TEXT in text.orEmpty(), text)
            assertFalse(secretAnswer in text.orEmpty(), text)
        }
        assertEquals("GeminiRequest(redacted)", request().toString())
    }

    @Test fun `a call inside a transaction fails before any request`() {
        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertFailsWith<IllegalStateException> { client().generate(KEY, request()) }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
            TransactionSynchronizationManager.clearSynchronization()
        }
        assertTrue(seen.isEmpty())
    }

    private fun proxy(port: Int): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
        this.executor = this@HttpGeminiClientTest.executor
        createContext("/") { exchange ->
            val headers = exchange.requestHeaders
            seen.add(Seen(exchange.requestMethod, exchange.requestURI, headers.getFirst("x-goog-api-key"), headers.getFirst("User-Agent"),
                headers.getFirst("Content-Type"), headers.getFirst("Accept"), exchange.requestBody.readAllBytes().decodeToString()))
            val current = reply
            if (current.delayMillis > 0) Thread.sleep(current.delayMillis)
            val bytes = current.body.toByteArray()
            exchange.sendResponseHeaders(current.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private fun config(proxyPort: Int = server!!.address.port, thinkingBudget: Int? = null) = AiSummaryConfig(
        enabled = true,
        model = MODEL,
        baseUrl = URI.create("http://generativelanguage.test"),
        proxyHost = "127.0.0.1",
        proxyPort = proxyPort,
        dailyRequestBudget = 10,
        thinkingBudget = thinkingBudget,
        connectTimeout = Duration.ofSeconds(2),
        requestTimeout = Duration.ofMillis(500),
    )

    private fun client(proxyPort: Int = server!!.address.port, thinkingBudget: Int? = null) =
        HttpGeminiClient(config(proxyPort, thinkingBudget), mapper)

    private fun request() = GeminiRequest(SYSTEM, USER_TEXT, SCHEMA)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun error(code: Int, status: String, reason: String?): String = mapper.writeValueAsString(mapOf("error" to mapOf(
        "code" to code, "message" to "synthetic upstream message", "status" to status,
        "details" to listOfNotNull(reason?.let { mapOf("@type" to "type.googleapis.com/google.rpc.ErrorInfo", "reason" to it) }),
    )))

    private companion object {
        /** Built from parts, so a search for leaked keys stays empty. */
        val KEY = "AIza" + "0".repeat(35)
        const val MODEL = "gemini-test-model"
        const val SYSTEM = "Синтетическая системная инструкция"
        const val USER_TEXT = "Синтетический текст отзывов"
        val SCHEMA: JsonNode = jacksonObjectMapper().readTree("""{"type": "OBJECT", "properties": {"a": {"type": "INTEGER"}}}""")
        const val OK_BODY = """{"candidates": [{"content": {"parts": [{"text": "{}"}]}, "finishReason": "STOP"}]}"""
    }
}
