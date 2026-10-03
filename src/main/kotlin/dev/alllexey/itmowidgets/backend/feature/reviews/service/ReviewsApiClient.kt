package dev.alllexey.itmowidgets.backend.feature.reviews.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** One provider review; strings are trimmed and blank optional strings are null. */
data class ReviewsComment(
    val id: Long,
    /** The provider's date as written; empty when it has none. */
    val dateRaw: String,
    val text: String,
    val subjectTitle: String?,
    val sourceTitle: String?,
    val sourceLink: String?,
)

data class ReviewsTeacher(val id: Long, val name: String, val comments: List<ReviewsComment>)

sealed interface RegistryResult {
    data object NotModified : RegistryResult

    /** ISU teacher ids only, without repeats, in ascending order. */
    data class Changed(val etag: String?, val teacherIds: List<Long>) : RegistryResult
}

interface ReviewsApiClient {
    fun registry(etag: String?): RegistryResult

    /** Null when the provider no longer knows the teacher. */
    fun teacher(id: Long): ReviewsTeacher?
}

/** Bounded operational categories, never provider messages or response payloads. */
enum class ReviewSyncErrorCategory { NETWORK, HTTP, MAPPING, PERSISTENCE, INTERNAL }

class ReviewsSyncFailure(val category: ReviewSyncErrorCategory, val where: String, val status: Int? = null) :
    RuntimeException("Reviews sync failed") {
    /** A short technical line such as `HTTP 503 /teacher/100123`, safe for logs and the admin page. */
    fun summary(): String = listOfNotNull(category.name, status?.toString(), where.takeIf(String::isNotEmpty)).joinToString(" ")
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReviewsRegistryPayload(val original: Map<String, Long?>? = null, val normalized: Map<String, Long?>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReviewsTeacherPayload(val id: Long? = null, val name: String? = null, val comments: List<ReviewsCommentPayload?>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReviewsCommentPayload(
    val id: Long? = null,
    val date: String? = null,
    val text: String? = null,
    val subject: ReviewsTitledPayload? = null,
    val source: ReviewsTitledPayload? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReviewsTitledPayload(val title: String? = null, val link: String? = null)

@Service
class HttpReviewsApiClient(private val config: ReviewsSyncConfig, private val objectMapper: ObjectMapper) : ReviewsApiClient {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(config.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun registry(etag: String?): RegistryResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Reviews I/O requires no transaction" }
        val path = "/registry"
        return send(path, etag) { status, headers, body ->
            when (status) {
                304 -> RegistryResult.NotModified

                200 -> {
                    val payload = parse(path, body, ReviewsRegistryPayload::class.java)
                    val ids = (payload.original.orEmpty().values + payload.normalized.orEmpty().values)
                        .filterNotNull().filter(ReviewTeachers::isIsu).distinct().sorted()
                    if (ids.isEmpty()) throw ReviewsSyncFailure(ReviewSyncErrorCategory.MAPPING, "$path empty")
                    RegistryResult.Changed(
                        headers.firstValue("ETag").orElse(null)?.trim()?.takeIf {
                            it.isNotEmpty() &&
                                it.length <= MAX_ETAG
                        },
                        ids,
                    )
                }

                else -> throw ReviewsSyncFailure(ReviewSyncErrorCategory.HTTP, path, status)
            }
        }
    }

    override fun teacher(id: Long): ReviewsTeacher? {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Reviews I/O requires no transaction" }
        val path = "/teacher/$id"
        return send(path, null) { status, _, body ->
            when (status) {
                404 -> null

                200 -> {
                    val payload = parse(path, body, ReviewsTeacherPayload::class.java)
                    if (payload.id != id) throw ReviewsSyncFailure(ReviewSyncErrorCategory.MAPPING, path)
                    val comments = payload.comments ?: throw ReviewsSyncFailure(ReviewSyncErrorCategory.MAPPING, path)
                    ReviewsTeacher(id, clean(payload.name) ?: "#$id", comments.mapNotNull { comment(path, it) })
                }

                else -> throw ReviewsSyncFailure(ReviewSyncErrorCategory.HTTP, path, status)
            }
        }
    }

    private fun comment(path: String, payload: ReviewsCommentPayload?): ReviewsComment? {
        val id = payload?.id ?: throw ReviewsSyncFailure(ReviewSyncErrorCategory.MAPPING, path)
        val text = clean(payload.text) ?: return null
        return ReviewsComment(
            id = id,
            dateRaw = clean(payload.date).orEmpty(),
            text = text,
            subjectTitle = clean(payload.subject?.title),
            sourceTitle = clean(payload.source?.title),
            sourceLink = clean(payload.source?.link)?.takeIf(::isWebLink),
        )
    }

    private fun <T> send(path: String, etag: String?, handle: (Int, HttpHeaders, ByteArray?) -> T): T {
        val request = HttpRequest.newBuilder(URI.create(config.baseUrl.toString().trimEnd('/') + path))
            .timeout(config.requestTimeout)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .apply { if (etag != null) header("If-None-Match", etag) }
            .GET()
            .build()
        val (response, body) = try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response to response.body().use { if (response.statusCode() == 200) readLimited(path, it) else null }
        } catch (_: IOException) {
            throw ReviewsSyncFailure(ReviewSyncErrorCategory.NETWORK, path)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ReviewsSyncFailure(ReviewSyncErrorCategory.NETWORK, path)
        }
        return handle(response.statusCode(), response.headers(), body)
    }

    private fun readLimited(path: String, stream: InputStream): ByteArray {
        val bytes = stream.readNBytes(MAX_BODY_BYTES + 1)
        if (bytes.size > MAX_BODY_BYTES) throw ReviewsSyncFailure(ReviewSyncErrorCategory.MAPPING, path)
        return bytes
    }

    private fun <T : Any> parse(path: String, body: ByteArray?, type: Class<T>): T = try {
        body?.let { objectMapper.readValue(it, type) }
    } catch (_: IOException) {
        null
    } ?: throw ReviewsSyncFailure(ReviewSyncErrorCategory.MAPPING, path)

    private fun clean(value: String?): String? = value?.replace("\u0000", "")?.trim()?.takeIf(String::isNotEmpty)

    private fun isWebLink(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()
    } catch (_: URISyntaxException) {
        false
    }

    companion object {
        const val USER_AGENT = "ITMO.Widgets reviews sync (+https://widgets.alllexey.dev)"
        private const val MAX_BODY_BYTES = 5 * 1024 * 1024
        private const val MAX_ETAG = 200
    }
}
