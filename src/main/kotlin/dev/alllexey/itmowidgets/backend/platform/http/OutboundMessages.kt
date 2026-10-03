package dev.alllexey.itmowidgets.backend.platform.http

import java.net.URI
import java.net.http.HttpHeaders

/** One outbound request. Header values and the body may be secret, so [toString] shows header names only. */
class OutboundRequest(
    val uri: URI,
    val headers: Map<String, String> = emptyMap(),
    val method: String = "GET",
    val body: ByteArray? = null,
) {
    override fun toString(): String =
        "OutboundRequest($method ${HttpRedaction.target(uri)}, headers=${HttpRedaction.headerNames(headers.keys)}, " +
            "body=${body?.let { "${it.size} bytes" } ?: "none"})"
}

/** [uri] is the URI that answered, after any redirect the client followed. */
class OutboundResponse(val uri: URI, val status: Int, val headers: HttpHeaders, val body: ResponseBody) {
    override fun toString(): String =
        "OutboundResponse($status ${HttpRedaction.target(uri)}, headers=${HttpRedaction.headerNames(headers.map().keys)}, body=$body)"
}

sealed interface ResponseBody {
    /** The caller did not ask for this status's body; the stream was closed unread. */
    data object NotRead : ResponseBody

    /** The body exceeded the client's limit; nothing of it is kept. */
    data object TooLarge : ResponseBody

    class Bytes(val bytes: ByteArray) : ResponseBody {
        override fun toString(): String = "Bytes(${bytes.size})"
    }

    /** The bytes, or null for [NotRead] and [TooLarge]. */
    fun bytesOrNull(): ByteArray? = (this as? Bytes)?.bytes
}

enum class OutboundHttpError { NETWORK, TOO_MANY_REDIRECTS, BAD_REDIRECT }

/**
 * An outbound request that produced no usable response. The message names the error, the method, the redacted target
 * and the class of the I/O cause; the cause itself is not attached, because JDK messages may quote the full URI.
 */
class OutboundHttpFailure(val error: OutboundHttpError, method: String, uri: URI, cause: Throwable? = null) :
    RuntimeException(
        listOfNotNull(error.name, method, HttpRedaction.target(uri), cause?.javaClass?.simpleName).joinToString(" "),
    )
