package dev.alllexey.itmowidgets.backend.platform.http

import java.net.URI

/**
 * Text forms of outbound requests that are safe for logs. Query strings carry session numbers and header values carry
 * keys and cookies, so neither appears; user info and fragments are dropped too.
 */
object HttpRedaction {
    const val REDACTED = "<redacted>"

    /** `scheme://host[:port]/path`, with `?<redacted>` when the URI has a query. */
    fun target(uri: URI): String = buildString {
        uri.scheme?.let { append(it).append("://") }
        append(uri.host ?: REDACTED)
        if (uri.port != -1) append(':').append(uri.port)
        append(uri.rawPath.orEmpty())
        if (uri.rawQuery != null) append('?').append(REDACTED)
    }

    /** The header names in their order, without values. */
    fun headerNames(names: Collection<String>): String = names.joinToString(prefix = "[", postfix = "]")
}
