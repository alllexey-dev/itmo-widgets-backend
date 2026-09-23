package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** [url] is opened by clients; [normalizedUrl] identifies duplicates. */
data class NormalizedResource(val url: String, val normalizedUrl: String)

/**
 * Syntax-only policy for any HTTPS site. Neither validation nor moderation ever dereferences a
 * submitted URL, so the host is not restricted; moderation and reports handle the content.
 */
object ResourceUrlPolicy {
    private val spreadsheet = Regex("^/spreadsheets/d/([A-Za-z0-9_-]+)(?:/.*)?$")

    fun normalize(raw: String): NormalizedResource {
        val input = raw.trim()
        if (input.isEmpty() || input.length > 2000 || input.any { it.isWhitespace() || it.isISOControl() }) invalid()
        val uri = try {
            val ascii = asciiHost(URI(input)).toASCIIString()
            if (ascii.length > 2000) invalid()
            URI(ascii)
        } catch (_: java.net.URISyntaxException) { invalid() }
        val host = uri.host?.lowercase(Locale.ROOT) ?: invalid()
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443)) invalid()
        val sheet = if (host == "docs.google.com") spreadsheet.matchEntire(uri.rawPath.orEmpty()) else null
        if (sheet != null) {
            val base = "https://docs.google.com/spreadsheets/d/${sheet.groupValues[1]}"
            val gid = (parts(uri.rawQuery) + parts(uri.rawFragment)).firstOrNull {
                key(it) == "gid" && it.substringAfter('=', "").matches(Regex("[0-9]+"))
            }?.substringAfter('=')
            return NormalizedResource(base + (gid?.let { "#gid=$it" } ?: ""), base)
        }
        val query = parts(uri.rawQuery).filterNot { key(it).let { name -> name.startsWith("utm_") || name == "fbclid" } }
            .joinToString("&").takeIf { it.isNotEmpty() }
        val url = "https://$host" + uri.rawPath.orEmpty().trimEnd('/') + (query?.let { "?$it" } ?: "")
        if (url.length > 2000) invalid()
        return NormalizedResource(url, url)
    }

    /** `java.net.URI` leaves internationalized hosts such as `пример.рф` without a host; convert them to Punycode. */
    private fun asciiHost(uri: URI): URI {
        val authority = uri.rawAuthority
        if (uri.host != null || authority == null || '@' in authority || '%' in authority) return uri
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "").let { if (it.isEmpty()) "" else ":$it" }
        val ascii = try { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES) } catch (_: IllegalArgumentException) { invalid() }
        return URI("${uri.scheme}://$ascii$port${uri.rawPath.orEmpty()}" +
            (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: ""))
    }

    private fun parts(raw: String?): List<String> = raw?.split('&')?.filter { it.isNotEmpty() }.orEmpty()
    private fun key(part: String): String = try {
        URLDecoder.decode(part.substringBefore('='), StandardCharsets.UTF_8).lowercase(Locale.ROOT)
    } catch (_: IllegalArgumentException) { invalid() }
    private fun invalid(): Nothing = throw InvalidRequestDataException("Only HTTPS links without credentials or a custom port are allowed")
}
