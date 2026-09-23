package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class ResourceType { GOOGLE_SHEET, LINK }

data class NormalizedResource(val type: ResourceType, val url: String, val normalizedUrl: String)

/** Syntax-only policy. Neither validation nor moderation ever dereferences a submitted URL. */
object ResourceUrlPolicy {
    private val hosts = setOf("docs.google.com", "drive.google.com", "notion.so", "github.com", "disk.yandex.ru", "lms.itmo.ru")
    private val spreadsheet = Regex("^/spreadsheets/d/([A-Za-z0-9_-]+)(?:/.*)?$")

    fun normalize(raw: String): NormalizedResource {
        val input = raw.trim()
        if (input.isEmpty() || input.length > 2000 || input.any { it.isWhitespace() || it.isISOControl() }) invalid()
        val uri = try {
            val ascii = URI(input).toASCIIString()
            if (ascii.length > 2000) invalid()
            URI(ascii)
        } catch (_: java.net.URISyntaxException) { invalid() }
        val host = uri.host?.lowercase(Locale.ROOT) ?: invalid()
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443) || !(host in hosts || host.endsWith(".notion.site"))) invalid()
        val sheet = if (host == "docs.google.com") spreadsheet.matchEntire(uri.rawPath.orEmpty()) else null
        if (sheet != null) {
            val base = "https://docs.google.com/spreadsheets/d/${sheet.groupValues[1]}"
            val gid = (parts(uri.rawQuery) + parts(uri.rawFragment)).firstOrNull {
                key(it) == "gid" && it.substringAfter('=', "").matches(Regex("[0-9]+"))
            }?.substringAfter('=')
            return NormalizedResource(ResourceType.GOOGLE_SHEET, base + (gid?.let { "#gid=$it" } ?: ""), base)
        }
        val query = parts(uri.rawQuery).filterNot { key(it).let { name -> name.startsWith("utm_") || name == "fbclid" } }
            .joinToString("&").takeIf { it.isNotEmpty() }
        val url = "https://$host" + uri.rawPath.orEmpty().trimEnd('/') + (query?.let { "?$it" } ?: "")
        if (url.length > 2000) invalid()
        return NormalizedResource(ResourceType.LINK, url, url)
    }

    private fun parts(raw: String?): List<String> = raw?.split('&')?.filter { it.isNotEmpty() }.orEmpty()
    private fun key(part: String): String = try {
        URLDecoder.decode(part.substringBefore('='), StandardCharsets.UTF_8).lowercase(Locale.ROOT)
    } catch (_: IllegalArgumentException) { invalid() }
    private fun invalid(): Nothing = throw InvalidRequestDataException("Only supported HTTPS resource URLs are allowed")
}
