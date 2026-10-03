package dev.alllexey.itmowidgets.backend.platform.http

import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** How an [OutboundHttpClient] treats 3xx responses. */
sealed interface RedirectPolicy {
    /** Every 3xx response goes back to the caller. */
    data object Never : RedirectPolicy

    /** The JDK follows redirects, except from `https` to `http` (`HttpClient.Redirect.NORMAL`). */
    data object Normal : RedirectPolicy

    /**
     * The client follows up to [max] redirects of a `GET` itself: each hop is a new `GET` with the same headers and
     * its own request timeout, and the response carries the URI of the last hop. Locations may be relative.
     */
    data class Manual(val max: Int) : RedirectPolicy {
        init {
            require(max >= 0) { "The redirect limit must not be negative" }
        }
    }
}

/**
 * The fixed part of an outbound client. [maxBodyBytes] bounds every body the client reads; a longer body is
 * [ResponseBody.TooLarge] and is never kept in memory. [cookies] and [proxy] are passed to the JDK client as given.
 */
data class OutboundHttpSettings(
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val maxBodyBytes: Int,
    val redirects: RedirectPolicy,
    val proxy: ProxySelector? = null,
    val cookies: CookieHandler? = null,
) {
    init {
        require(connectTimeout > Duration.ZERO) { "The connect timeout must be positive" }
        require(requestTimeout > Duration.ZERO) { "The request timeout must be positive" }
        require(maxBodyBytes > 0) { "The body limit must be positive" }
    }
}

/**
 * The one `java.net.http` wrapper of Backend. It applies the timeouts, the body limit and the redirect policy of its
 * [OutboundHttpSettings] and reports I/O problems as [OutboundHttpFailure], whose text never carries a query string,
 * a header value or a body. Callers map the failure to their own bounded categories.
 */
class OutboundHttpClient(private val settings: OutboundHttpSettings) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(settings.connectTimeout)
        .followRedirects(if (settings.redirects == RedirectPolicy.Normal) HttpClient.Redirect.NORMAL else HttpClient.Redirect.NEVER)
        .apply {
            settings.proxy?.let { proxy(it) }
            settings.cookies?.let { cookieHandler(it) }
        }
        .build()

    /** Sends [request]; the body of a final response is read only when [readBody] accepts its status. */
    fun send(request: OutboundRequest, readBody: (status: Int) -> Boolean = { true }): OutboundResponse {
        val manual = settings.redirects as? RedirectPolicy.Manual
        require(manual == null || request.method == "GET") { "Manual redirects are followed for GET only" }
        var uri = request.uri
        var redirects = 0
        while (true) {
            val response = exchange(request, uri)
            val status = response.statusCode()
            if (manual != null && status in REDIRECT_STATUSES) {
                close(response.body(), request, uri)
                if (redirects++ == manual.max) throw OutboundHttpFailure(OutboundHttpError.TOO_MANY_REDIRECTS, request.method, uri)
                val location = response.headers().firstValue("Location").orElse(null)
                uri = location?.let { resolve(uri, it) } ?: throw OutboundHttpFailure(OutboundHttpError.BAD_REDIRECT, request.method, uri)
                continue
            }
            val body = if (readBody(status)) {
                read(response.body(), request, uri)
            } else {
                close(response.body(), request, uri)
                ResponseBody.NotRead
            }
            return OutboundResponse(response.uri(), status, response.headers(), body)
        }
    }

    private fun exchange(request: OutboundRequest, uri: URI): HttpResponse<InputStream> {
        val builder = HttpRequest.newBuilder(uri).timeout(settings.requestTimeout)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val body = request.body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody()
        builder.method(request.method, body)
        return io(request, uri) { http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()) }
    }

    private fun read(stream: InputStream, request: OutboundRequest, uri: URI): ResponseBody = io(request, uri) {
        stream.use {
            val bytes = it.readNBytes(settings.maxBodyBytes + 1)
            if (bytes.size > settings.maxBodyBytes) ResponseBody.TooLarge else ResponseBody.Bytes(bytes)
        }
    }

    private fun close(stream: InputStream, request: OutboundRequest, uri: URI) = io(request, uri) { stream.close() }

    private fun <T> io(request: OutboundRequest, uri: URI, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw OutboundHttpFailure(OutboundHttpError.NETWORK, request.method, uri, e)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw OutboundHttpFailure(OutboundHttpError.NETWORK, request.method, uri, e)
    }

    companion object {
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        /** [location] resolved against [base]; null when it is not a URI. */
        fun resolve(base: URI, location: String): URI? = try {
            base.resolve(URI(location.trim()))
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
