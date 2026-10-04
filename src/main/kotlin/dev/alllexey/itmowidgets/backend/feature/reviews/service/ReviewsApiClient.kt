package dev.alllexey.itmowidgets.backend.feature.reviews.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import dev.alllexey.itmowidgets.backend.platform.http.OutboundHttpClient
import dev.alllexey.itmowidgets.backend.platform.http.OutboundHttpFailure
import dev.alllexey.itmowidgets.backend.platform.http.OutboundHttpSettings
import dev.alllexey.itmowidgets.backend.platform.http.OutboundRequest
import dev.alllexey.itmowidgets.backend.platform.http.RedirectPolicy
import dev.alllexey.itmowidgets.backend.platform.http.ResponseBody
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpHeaders

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
class HttpReviewsApiClient(private val config: ReviewsSyncConfig, private val jsonMapper: JsonMapper) : ReviewsApiClient {
    private val http = OutboundHttpClient(
        OutboundHttpSettings(
            connectTimeout = config.connectTimeout,
            requestTimeout = config.requestTimeout,
            maxBodyBytes = MAX_BODY_BYTES,
            redirects = RedirectPolicy.Normal,
        ),
    )

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
        val headers = buildMap {
            put("User-Agent", USER_AGENT)
            put("Accept", "application/json")
            if (etag != null) put("If-None-Match", etag)
        }
        val response = try {
            http.send(OutboundRequest(URI.create(config.baseUrl.toString().trimEnd('/') + path), headers)) { it == 200 }
        } catch (_: OutboundHttpFailure) {
            throw ReviewsSyncFailure(ReviewSyncErrorCategory.NETWORK, path)
        }
        if (response.body == ResponseBody.TooLarge) throw ReviewsSyncFailure(ReviewSyncErrorCategory.MAPPING, path)
        return handle(response.status, response.headers, response.body.bytesOrNull())
    }

    private fun <T : Any> parse(path: String, body: ByteArray?, type: Class<T>): T = try {
        body?.let { jsonMapper.readValue(it, type) }
    } catch (_: JacksonException) {
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
