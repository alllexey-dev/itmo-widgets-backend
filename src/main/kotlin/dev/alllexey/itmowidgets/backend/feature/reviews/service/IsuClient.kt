package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.platform.http.OutboundHttpClient
import dev.alllexey.itmowidgets.backend.platform.http.OutboundHttpError
import dev.alllexey.itmowidgets.backend.platform.http.OutboundHttpFailure
import dev.alllexey.itmowidgets.backend.platform.http.OutboundHttpSettings
import dev.alllexey.itmowidgets.backend.platform.http.OutboundRequest
import dev.alllexey.itmowidgets.backend.platform.http.RedirectPolicy
import dev.alllexey.itmowidgets.backend.platform.http.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.CookieStore
import java.net.HttpCookie
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Read-only access to ISU flow pages. Returns numbers only: names and groups are never read. */
interface IsuClient {
    fun login(identity: String): IsuSession

    /** ISU numbers of the flow members on [date]; empty when ISU reports no data. */
    fun members(session: IsuSession, potokId: Long, date: LocalDate): Set<Int>

    /** ISU numbers of the teachers in the flow schedule; empty is valid. */
    fun teachers(session: IsuSession, potokId: Long): Set<Int>
}

/**
 * An APEX session. [rotatedIdentity] is the `KEYCLOAK_IDENTITY` Keycloak issued during the login when it
 * differs from the one used. ISU may silently replace the session number; the client then updates [sessionId].
 */
class IsuSession(
    sessionId: String,
    val rotatedIdentity: String?,
    val rotatedExpiresAt: Instant?,
    internal val http: OutboundHttpClient? = null,
) {
    @Volatile
    var sessionId: String = sessionId
        internal set

    override fun toString(): String = "IsuSession(redacted)"
}

/** Bounded operational categories, never ISU messages or page content. */
enum class IsuErrorCategory { EXPIRED, SESSION_LOST, NETWORK, HTTP, MAPPING }

/**
 * A failed ISU request. [reason] tells a `MAPPING` failure apart (`no-report`, `bad-isu`, `no-session`, `too-many-pages`,
 * `too-large`, `unreadable`, `redirects`): a fixed word, never page content.
 */
class IsuFailure(val category: IsuErrorCategory, val where: String, val status: Int? = null, val reason: String? = null) :
    RuntimeException("ISU request failed") {
    /** A short technical line such as `HTTP 503 members/93724` or `MAPPING members/93724 no-report`, safe for logs and `last_error`. */
    fun summary(): String = listOfNotNull(category.name, status?.toString(), where.takeIf(String::isNotEmpty), reason).joinToString(" ")

    fun credentialStatus(): ServiceCredentialStatus =
        if (category == IsuErrorCategory.EXPIRED) ServiceCredentialStatus.EXPIRED else ServiceCredentialStatus.FAILED
}

@Service
class HttpIsuClient(private val config: IsuConfig, private val clock: Clock) : IsuClient {
    private val apexPath = config.baseUrl.path.trimEnd('/') + "/pls/apex/"
    private var lastRequestAt: Long? = null

    override fun login(identity: String): IsuSession {
        requireNoTransaction()
        val cookies = CookieManager(NetscapeCookieStore(CookieManager().cookieStore), CookiePolicy.ACCEPT_ALL)
        cookies.cookieStore.add(
            config.identityUrl,
            HttpCookie(IDENTITY_COOKIE, identity).apply {
                domain = config.identityUrl.host
                path = config.identityUrl.path
                secure = config.identityUrl.scheme == "https"
                version = 0
            },
        )
        val http = OutboundHttpClient(
            OutboundHttpSettings(
                connectTimeout = config.connectTimeout,
                requestTimeout = config.requestTimeout,
                maxBodyBytes = MAX_BODY_BYTES,
                redirects = RedirectPolicy.Manual(MAX_REDIRECTS),
                cookies = cookies,
            ),
        )
        pace()
        val page = fetch(http, apexUri("f?p=$APP:1"), LOGIN)
        if (isIdentity(page.uri)) throw IsuFailure(IsuErrorCategory.EXPIRED, LOGIN)
        if (page.status != 200) throw IsuFailure(IsuErrorCategory.HTTP, LOGIN, page.status)
        if (page.document(LOGIN).selectFirst(LOGIN_FORM) != null) throw IsuFailure(IsuErrorCategory.EXPIRED, LOGIN)
        val sessionId = sessionOf(page.uri) ?: throw IsuFailure(IsuErrorCategory.MAPPING, LOGIN, reason = "no-session")
        val current = cookies.cookieStore.get(config.identityUrl).lastOrNull { it.name == IDENTITY_COOKIE && !it.hasExpired() }
        val rotated = current?.takeIf { it.value.isNotEmpty() && it.value != identity }
        val expiresAt = rotated?.maxAge?.takeIf { it > 0 }?.let { clock.instant().plusSeconds(it) }
        return IsuSession(sessionId, rotated?.value, expiresAt, http)
    }

    override fun members(session: IsuSession, potokId: Long, date: LocalDate): Set<Int> {
        requireNoTransaction()
        val where = "members/$potokId"
        val members = linkedSetOf<Int>()
        var uri = apexUri("f?p=$APP:GR:${session.sessionId}::NO:RP:GR_TYPE,GR_DATE,ID_POTOK,ID_DISTP:potok,${date.format(DATE)},$potokId,")
        var minRow = 1
        repeat(MAX_MEMBER_PAGES) { index ->
            val document = reportPage(session, uri, where)
            val table = document.selectFirst(MEMBERS_REPORT)
            if (table == null) {
                if (index == 0 && document.selectFirst(NO_DATA) != null) return emptySet()
                throw IsuFailure(IsuErrorCategory.MAPPING, where, reason = "no-report")
            }
            table.select("td[headers=ЧЛВК_ИД]").forEach { cell ->
                members += cell.text().trim().toIntOrNull()?.takeIf { it > 0 }
                    ?: throw IsuFailure(IsuErrorCategory.MAPPING, where, reason = "bad-isu")
            }
            val next = nextPage(document, minRow) ?: return members
            minRow = next.first
            uri = next.second
        }
        throw IsuFailure(IsuErrorCategory.MAPPING, where, reason = "too-many-pages")
    }

    /**
     * A page of the members report. ISU now and then serves it with neither the report nor `nodatafound` (4 of 59
     * requests in a week of November 2026), and the same flow reads fine later, so such a page is asked once more.
     */
    private fun reportPage(session: IsuSession, uri: URI, where: String): Document {
        pace()
        val document = page(session, uri, where)
        if (document.selectFirst(MEMBERS_REPORT) != null || document.selectFirst(NO_DATA) != null) return document
        pace()
        return page(session, uri, where)
    }

    override fun teachers(session: IsuSession, potokId: Long): Set<Int> {
        requireNoTransaction()
        val where = "teachers/$potokId"
        pace()
        val document =
            page(
                session,
                apexUri("f?p=$APP:15:${session.sessionId}::NO::SCH,SCH_POTOK_ID,SCH_TYPE,SCH_WEEK,SCH_ID,SCH_FOUND:1,$potokId,5,2,,TRUE"),
                where,
            )
        return document.select("a[href*=PID:]").flatMapTo(linkedSetOf()) { link ->
            PERSON.findAll(link.attr("href")).map { match ->
                match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
                    ?: throw IsuFailure(IsuErrorCategory.MAPPING, where, reason = "bad-isu")
            }
        }
    }

    /**
     * The members report is an APEX classic report. When APEX renders pagination links (`pg_R_<region>` with
     * `pg_min_row`), the link with the smallest first row after [minRow] is the next page.
     */
    private fun nextPage(document: Document, minRow: Int): Pair<Int, URI>? = document.select("a[href*=pg_min_row]").mapNotNull { link ->
        val row = MIN_ROW.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val uri = OutboundHttpClient.resolve(URI.create(document.location()), link.attr("href"))?.takeIf(::isApex) ?: return@mapNotNull null
        (row to uri).takeIf { row > minRow }
    }.minByOrNull { it.first }

    private fun page(session: IsuSession, start: URI, where: String): Document {
        val http = requireNotNull(session.http) { "The session was not created by this client" }
        val page = fetch(http, start, where)
        if (isIdentity(page.uri)) throw IsuFailure(IsuErrorCategory.SESSION_LOST, where)
        if (page.status != 200) throw IsuFailure(IsuErrorCategory.HTTP, where, page.status)
        val document = page.document(where)
        if (document.selectFirst(LOGIN_FORM) != null || !isApex(page.uri)) throw IsuFailure(IsuErrorCategory.SESSION_LOST, where)
        val sessionId = sessionOf(page.uri) ?: throw IsuFailure(IsuErrorCategory.SESSION_LOST, where)
        // With a live ISU cookie APEX silently replaces an unknown session number and serves the page.
        if (sessionId != session.sessionId) session.sessionId = sessionId
        return document
    }

    private fun fetch(http: OutboundHttpClient, start: URI, where: String): Page {
        val request = OutboundRequest(start, mapOf("User-Agent" to config.userAgent, "Accept" to "text/html"))
        val response = try {
            http.send(request) { it == 200 }
        } catch (e: OutboundHttpFailure) {
            if (e.error == OutboundHttpError.NETWORK) throw IsuFailure(IsuErrorCategory.NETWORK, where)
            throw IsuFailure(IsuErrorCategory.MAPPING, where, reason = "redirects")
        }
        if (response.body == ResponseBody.TooLarge) throw IsuFailure(IsuErrorCategory.MAPPING, where, reason = "too-large")
        return Page(response.uri, response.status, response.body.bytesOrNull())
    }

    @Synchronized
    private fun pace() {
        val last = lastRequestAt
        if (last != null) {
            val wait = last + config.requestDelay.toNanos() - System.nanoTime()
            if (wait > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(wait)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IsuFailure(IsuErrorCategory.NETWORK, "pace")
                }
            }
        }
        lastRequestAt = System.nanoTime()
    }

    private fun apexUri(query: String): URI = URI.create(config.baseUrl.resolve(apexPath).toString() + query)

    private fun isIdentity(uri: URI): Boolean =
        sameOrigin(uri, config.identityUrl) && uri.path.orEmpty().startsWith(config.identityUrl.path)

    private fun isApex(uri: URI): Boolean =
        sameOrigin(uri, config.baseUrl) && uri.path.orEmpty().startsWith(apexPath) && uri.rawQuery.orEmpty().contains("p=$APP:")

    private fun sameOrigin(uri: URI, other: URI): Boolean =
        uri.scheme.equals(other.scheme, ignoreCase = true) && uri.host.equals(other.host, ignoreCase = true) && port(uri) == port(other)

    private fun port(uri: URI): Int = if (uri.port != -1) {
        uri.port
    } else if (uri.scheme.equals("https", true)) {
        443
    } else {
        80
    }

    private fun sessionOf(uri: URI): String? = SESSION.find(uri.toString())?.groupValues?.get(1)

    private fun requireNoTransaction() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "ISU I/O requires no transaction" }
    }

    private class Page(val uri: URI, val status: Int, val body: ByteArray?) {
        fun document(where: String): Document = try {
            Jsoup.parse(ByteArrayInputStream(body ?: ByteArray(0)), null, uri.toString())
        } catch (_: IOException) {
            throw IsuFailure(IsuErrorCategory.MAPPING, where, reason = "unreadable")
        }
    }

    /**
     * Keycloak's `Set-Cookie` with `Max-Age` parses as an RFC 2965 cookie, which `CookieManager` would send
     * back quoted with `$Version`. Browsers send plain `name=value`, so every stored cookie is kept at version 0.
     */
    private class NetscapeCookieStore(private val delegate: CookieStore) : CookieStore by delegate {
        override fun add(uri: URI?, cookie: HttpCookie) {
            cookie.version = 0
            delegate.add(uri, cookie)
        }
    }

    companion object {
        private const val APP = 2143
        private const val LOGIN = "login"
        private const val IDENTITY_COOKIE = "KEYCLOAK_IDENTITY"
        private const val LOGIN_FORM = "#kc-form-login"
        private const val MAX_REDIRECTS = 10
        private const val MAX_MEMBER_PAGES = 20
        private const val MAX_BODY_BYTES = 5 * 1024 * 1024
        private val SESSION = Regex("[?&]p=2143:[^:&]*:([0-9]+)")
        private val PERSON = Regex("PID:([0-9]+)")
        private val MIN_ROW = Regex("[?&]pg_min_row=([0-9]+)")
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        private const val MEMBERS_REPORT = "table[summary^=Список потока]"
        private const val NO_DATA = "span.nodatafound"
    }
}
