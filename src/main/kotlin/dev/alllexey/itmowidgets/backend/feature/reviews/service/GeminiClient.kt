package dev.alllexey.itmowidgets.backend.feature.reviews.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** One `generateContent` call of the Gemini API. */
interface GeminiClient {
    fun generate(apiKey: String, request: GeminiRequest): GeminiResponse
}

/** The prompt of one summary; review texts never appear in [toString]. */
class GeminiRequest(val systemInstruction: String, val userText: String, val responseSchema: JsonNode) {
    override fun toString(): String = "GeminiRequest(redacted)"
}

/** The parsed answer; the model output stays in memory and is never logged. */
data class GeminiResponse(
    val blockReason: String?,
    val candidates: List<GeminiCandidate>,
    val promptTokens: Int?,
    val outputTokens: Int?,
    val thoughtsTokens: Int?,
) {
    override fun toString(): String =
        "GeminiResponse(blockReason=$blockReason, candidates=${candidates.size}, promptTokens=$promptTokens, " +
            "outputTokens=$outputTokens, thoughtsTokens=$thoughtsTokens)"
}

/** [text] joins the candidate's parts without the `thought` ones. */
data class GeminiCandidate(val finishReason: String?, val text: String) {
    override fun toString(): String = "GeminiCandidate(finishReason=$finishReason, redacted)"
}

/** Bounded operational categories, never Gemini messages or response payloads. */
enum class GeminiErrorCategory { NETWORK, HTTP, RATE_LIMITED, AUTH, LOCATION, MAPPING }

/** [reason] is the key error reason that made the failure `AUTH`, such as `API_KEY_INVALID`. */
class GeminiFailure(val category: GeminiErrorCategory, val status: Int? = null, val reason: String? = null) :
    RuntimeException("Gemini request failed") {
    /** A short technical line such as `AUTH 400 API_KEY_INVALID`, safe for logs and `last_error`. */
    fun summary(): String = listOfNotNull(category.name, status?.toString(), reason).joinToString(" ")
}

/**
 * Talks to Gemini only through the configured HTTP proxy. The proxy address stays unresolved, so it is looked up
 * on every connection, and the key travels only in the `x-goog-api-key` header, never in the URL.
 */
@Service
class HttpGeminiClient(private val config: AiSummaryConfig, private val objectMapper: ObjectMapper) : GeminiClient {
    // Built on first use, so a disabled configuration without a proxy builds nothing.
    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .proxy(ProxySelector.of(InetSocketAddress.createUnresolved(config.proxyHost, config.proxyPort)))
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(config.connectTimeout)
            .build()
    }

    override fun generate(apiKey: String, request: GeminiRequest): GeminiResponse {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Gemini I/O requires no transaction" }
        val httpRequest = HttpRequest.newBuilder(endpoint())
            .timeout(config.requestTimeout)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body(request))))
            .build()
        val (status, body) = try {
            val response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
            response.statusCode() to response.body().use(::readLimited)
        } catch (_: IOException) {
            throw GeminiFailure(GeminiErrorCategory.NETWORK)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GeminiFailure(GeminiErrorCategory.NETWORK)
        }
        if (status == 200) return parse(body ?: throw GeminiFailure(GeminiErrorCategory.MAPPING))
        throw failure(status, body)
    }

    private fun endpoint(): URI = URI.create(config.baseUrl.toString().trimEnd('/') + "/v1beta/models/${config.model}:generateContent")

    private fun body(request: GeminiRequest): JsonNode = objectMapper.createObjectNode().apply {
        putObject("systemInstruction").putArray("parts").addObject().put("text", request.systemInstruction)
        putArray("contents").addObject().apply {
            put("role", "user")
            putArray("parts").addObject().put("text", request.userText)
        }
        putObject("generationConfig").apply {
            put("responseMimeType", "application/json")
            set<JsonNode>("responseSchema", request.responseSchema)
            put("temperature", config.temperature)
            put("maxOutputTokens", config.maxOutputTokens)
            config.thinkingBudget?.let { putObject("thinkingConfig").put("thinkingBudget", it) }
        }
    }

    /** Null when the body is larger than the limit. */
    private fun readLimited(stream: InputStream): ByteArray? {
        val bytes = stream.readNBytes(MAX_BODY_BYTES + 1)
        return bytes.takeIf { it.size <= MAX_BODY_BYTES }
    }

    private fun parse(body: ByteArray): GeminiResponse {
        val root = tree(body)?.takeIf(JsonNode::isObject) ?: throw GeminiFailure(GeminiErrorCategory.MAPPING)
        val candidates = root.get("candidates")
        if (candidates != null && !candidates.isNull && !candidates.isArray) throw GeminiFailure(GeminiErrorCategory.MAPPING)
        val usage = root.path("usageMetadata")
        return GeminiResponse(
            blockReason = root.path("promptFeedback").path("blockReason").textOrNull(),
            candidates = candidates?.takeIf(JsonNode::isArray)?.map(::candidate).orEmpty(),
            promptTokens = usage.path("promptTokenCount").intOrNull(),
            outputTokens = usage.path("candidatesTokenCount").intOrNull(),
            thoughtsTokens = usage.path("thoughtsTokenCount").intOrNull(),
        )
    }

    private fun candidate(node: JsonNode): GeminiCandidate {
        if (!node.isObject) throw GeminiFailure(GeminiErrorCategory.MAPPING)
        val parts = node.path("content").path("parts")
        val text = if (parts.isArray) {
            parts.filter { it.isObject && !it.path("thought").asBoolean(false) }
                .mapNotNull { it.path("text").textOrNull() }
                .joinToString("")
        } else {
            ""
        }
        return GeminiCandidate(node.path("finishReason").textOrNull(), text)
    }

    /** Reads only the status and whitelisted reasons of the error; the body itself is dropped. */
    private fun failure(status: Int, body: ByteArray?): GeminiFailure {
        val error = body?.let(::tree)?.path("error")
        val errorStatus = error?.path("status")?.textOrNull()
        val reasons = error?.path("details")?.takeIf(JsonNode::isArray)
            ?.mapNotNull { it.path("reason").textOrNull() }?.filter { it in KNOWN_REASONS }.orEmpty()
        val keyReason = reasons.firstOrNull { it in KEY_REASONS }
        return when {
            status == 429 -> GeminiFailure(GeminiErrorCategory.RATE_LIMITED, status)
            status == 401 || status == 403 || keyReason != null -> GeminiFailure(GeminiErrorCategory.AUTH, status, keyReason)
            errorStatus == "FAILED_PRECONDITION" -> GeminiFailure(GeminiErrorCategory.LOCATION, status)
            else -> GeminiFailure(GeminiErrorCategory.HTTP, status)
        }
    }

    private fun tree(body: ByteArray): JsonNode? = try {
        objectMapper.readTree(body)
    } catch (_: IOException) {
        null
    }

    private fun JsonNode.textOrNull(): String? = takeIf(JsonNode::isTextual)?.asText()

    private fun JsonNode.intOrNull(): Int? = takeIf(JsonNode::isInt)?.asInt()

    companion object {
        const val USER_AGENT = "ITMO.Widgets summaries"
        private const val MAX_BODY_BYTES = 1024 * 1024
        private val KEY_REASONS = setOf("API_KEY_INVALID", "API_KEY_EXPIRED")
        private val KNOWN_REASONS = KEY_REASONS + setOf(
            "RATE_LIMIT_EXCEEDED",
            "RESOURCE_EXHAUSTED",
            "FAILED_PRECONDITION",
            "PERMISSION_DENIED",
            "INVALID_ARGUMENT",
        )
    }
}
