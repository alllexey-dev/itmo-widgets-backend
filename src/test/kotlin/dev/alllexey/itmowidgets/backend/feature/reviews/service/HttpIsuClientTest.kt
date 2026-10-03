package dev.alllexey.itmowidgets.backend.feature.reviews.service

import com.sun.net.httpserver.HttpServer
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.*

class HttpIsuClientTest {
    private data class Reply(
        val status: Int,
        val body: String = "",
        val location: String? = null,
        val setCookie: String? = null,
        val delayMillis: Long = 0,
    )

    private data class Seen(val target: String, val cookie: String?, val userAgent: String?, val accept: String?, val at: Long)

    private val now = Instant.parse("2026-09-29T09:00:00Z")
    private val seen = CopyOnWriteArrayList<Seen>()
    private var route: (String) -> Reply = { Reply(404) }
    private lateinit var executor: ExecutorService
    private lateinit var server: HttpServer
    private lateinit var base: String

    @BeforeEach fun start() {
        executor = Executors.newCachedThreadPool()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@HttpIsuClientTest.executor
            createContext("/") { exchange ->
                val target = exchange.requestURI.toString()
                val headers = exchange.requestHeaders
                seen.add(
                    Seen(target, headers.getFirst("Cookie"), headers.getFirst("User-Agent"), headers.getFirst("Accept"), System.nanoTime()),
                )
                val reply = route(target)
                if (reply.delayMillis > 0) Thread.sleep(reply.delayMillis)
                reply.location?.let { exchange.responseHeaders.add("Location", it) }
                reply.setCookie?.let { exchange.responseHeaders.add("Set-Cookie", it) }
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                val bytes = reply.body.toByteArray()
                exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    // Generous by default so a slow CI runner does not turn a redirect chain into a timeout; the
    // timeout case passes its own short value.
    private fun config(requestDelay: Duration = Duration.ZERO, requestTimeout: Duration = Duration.ofSeconds(5)) = IsuConfig(
        keycloakIdentity = "seed-identity-value",
        baseUrl = URI.create(base),
        identityUrl = URI.create("$base/auth/realms/itmo/"),
        requestDelay = requestDelay,
        requestTimeout = requestTimeout,
        userAgent = "Synthetic Browser/1.0",
    )

    private fun client(requestDelay: Duration = Duration.ZERO, requestTimeout: Duration = Duration.ofSeconds(5)) =
        HttpIsuClient(config(requestDelay, requestTimeout), Clock.fixed(now, ZoneId.of("Europe/Moscow")))

    /** Login in one redirect: `f?p=2143:1` → `f?p=2143:1:111`. */
    private fun loggedIn(client: HttpIsuClient, pages: (String) -> Reply): IsuSession {
        route = { target ->
            when (target) {
                "/pls/apex/f?p=2143:1" -> Reply(302, location = "f?p=2143:1:111")
                "/pls/apex/f?p=2143:1:111" -> Reply(200, "<html><body>ISU</body></html>", setCookie = "ISU_AP_COOKIE=synthetic; Path=/")
                else -> pages(target)
            }
        }
        return client.login("seed-identity-value").also { seen.clear() }
    }

    @Test fun `login follows seven redirects through Keycloak with the seeded cookie and reads the session number`() {
        route = { target ->
            when (target) {
                "/pls/apex/f?p=2143:1" -> Reply(302, location = "$base/auth/realms/itmo/protocol/openid-connect/auth?client_id=isu")
                "/auth/realms/itmo/protocol/openid-connect/auth?client_id=isu" -> Reply(302, location = "../../login-actions/restart")
                "/auth/realms/itmo/login-actions/restart" -> Reply(302, location = "$base/pls/apex/sso?step=3")
                "/pls/apex/sso?step=3" -> Reply(303, location = "/auth/realms/itmo/broker?step=4")
                "/auth/realms/itmo/broker?step=4" -> Reply(307, location = "$base/pls/apex/sso?step=5")
                "/pls/apex/sso?step=5" -> Reply(301, location = "sso?step=6")
                "/pls/apex/sso?step=6" -> Reply(308, location = "f?p=2143:1:123456789012345")
                "/pls/apex/f?p=2143:1:123456789012345" -> Reply(200, "<html><body><div id=\"main\">ISU</div></body></html>")
                else -> Reply(404)
            }
        }

        val session = client().login("seed-identity-value")

        assertEquals("123456789012345", session.sessionId)
        assertNull(session.rotatedIdentity)
        assertNull(session.rotatedExpiresAt)
        assertEquals(8, seen.size)
        val firstIdentity = seen.first { it.target.startsWith("/auth/realms/itmo/") }
        assertEquals("KEYCLOAK_IDENTITY=seed-identity-value", firstIdentity.cookie)
        assertNull(seen.first().cookie, "The identity cookie is sent only to the identity realm")
        assertTrue(seen.all { it.userAgent == "Synthetic Browser/1.0" && it.accept == "text/html" })
    }

    @Test fun `a rotated identity cookie is returned with its lifetime and sent back in the plain form`() {
        route = { target ->
            when (target) {
                "/pls/apex/f?p=2143:1" -> Reply(302, location = "$base/auth/realms/itmo/auth")

                "/auth/realms/itmo/auth" -> Reply(
                    302,
                    location = "$base/auth/realms/itmo/after",
                    setCookie = "KEYCLOAK_IDENTITY=rotated-identity-value; Version=1; Path=/auth/realms/itmo/; Max-Age=7776000; HttpOnly",
                )

                "/auth/realms/itmo/after" -> Reply(302, location = "$base/pls/apex/f?p=2143:1:777")

                "/pls/apex/f?p=2143:1:777" -> Reply(200, "<html></html>")

                else -> Reply(404)
            }
        }

        val session = client().login("seed-identity-value")

        assertEquals("rotated-identity-value", session.rotatedIdentity)
        assertEquals(now.plus(Duration.ofDays(90)), session.rotatedExpiresAt)
        assertEquals("KEYCLOAK_IDENTITY=rotated-identity-value", seen.single { it.target == "/auth/realms/itmo/after" }.cookie)
        assertFalse(session.toString().contains("rotated-identity-value"))

        seen.clear()
        route = { target ->
            when (target) {
                "/pls/apex/f?p=2143:1" -> Reply(302, location = "$base/auth/realms/itmo/auth")

                "/auth/realms/itmo/auth" -> Reply(
                    302,
                    location = "$base/pls/apex/f?p=2143:1:778",
                    setCookie = "KEYCLOAK_IDENTITY=seed-identity-value; Path=/auth/realms/itmo/; Max-Age=7776000",
                )

                "/pls/apex/f?p=2143:1:778" -> Reply(200, "<html></html>")

                else -> Reply(404)
            }
        }
        val same = client().login("seed-identity-value")
        assertEquals("778", same.sessionId)
        assertNull(same.rotatedIdentity)
        assertNull(same.rotatedExpiresAt)
    }

    @Test fun `the Keycloak login form means the identity cookie expired`() {
        route = { target ->
            when (target) {
                "/pls/apex/f?p=2143:1" -> Reply(302, location = "$base/auth/realms/itmo/protocol/openid-connect/auth")
                else -> Reply(200, "<html><form id=\"kc-form-login\" action=\"#\"></form></html>")
            }
        }
        assertEquals("EXPIRED login", assertFailsWith<IsuFailure> { client().login("seed-identity-value") }.summary())

        route = { Reply(200, "<html><form id=\"kc-form-login\"></form></html>") }
        assertEquals("EXPIRED login", assertFailsWith<IsuFailure> { client().login("seed-identity-value") }.summary())
    }

    @Test fun `too many redirects server errors and timeouts fail the login with bounded summaries`() {
        route = { target -> Reply(302, location = "/pls/apex/loop?n=${target.length}") }
        assertEquals("MAPPING login", assertFailsWith<IsuFailure> { client().login("seed-identity-value") }.summary())
        assertEquals(11, seen.size, "The initial request and ten redirects")

        route = { Reply(503, "Service unavailable") }
        val unavailable = assertFailsWith<IsuFailure> { client().login("seed-identity-value") }
        assertEquals("HTTP 503 login", unavailable.summary())
        assertEquals(503, unavailable.status)

        route = { Reply(200, "<html></html>", delayMillis = 1_000) }
        assertEquals(
            "NETWORK login",
            assertFailsWith<IsuFailure> {
                client(requestTimeout = Duration.ofMillis(300)).login("seed-identity-value")
            }.summary(),
        )

        route = { target -> if (target == "/pls/apex/f?p=2143:1") Reply(200, "<html></html>") else Reply(404) }
        assertEquals("MAPPING login", assertFailsWith<IsuFailure> { client().login("seed-identity-value") }.summary())
    }

    @Test fun `members are read from the flow list with encoded and plain header attributes`() {
        val client = client()
        val session = loggedIn(client) { target ->
            if (target == "/pls/apex/f?p=2143:GR:111::NO::GR_TYPE,GR_DATE,ID_POTOK,ID_DISTP:potok,29.09.2026,93724,") {
                Reply(200, membersPage(listOf("100001", " 100002 ", "100003", "100001"), encodedHeaders = true))
            } else {
                Reply(404)
            }
        }

        assertEquals(setOf(100001, 100002, 100003), client.members(session, 93724, LocalDate.of(2026, 9, 29)))
        assertTrue(seen.single().cookie.orEmpty().contains("ISU_AP_COOKIE=synthetic"), "ISU cookies from the login are kept")

        val plain = loggedIn(client) { Reply(200, membersPage(listOf("100004"), encodedHeaders = false)) }
        assertEquals(setOf(100004), client.members(plain, 93724, LocalDate.of(2026, 9, 29)))
    }

    @Test fun `an empty flow is empty and unreadable pages are mapping failures`() {
        val client = client()
        val empty = loggedIn(client) { Reply(200, "<html><body><span class=\"nodatafound\">Нет данных</span></body></html>") }
        assertEquals(emptySet(), client.members(empty, 93724, LocalDate.of(2026, 9, 29)))

        val letters = loggedIn(client) { Reply(200, membersPage(listOf("100001", "abc"), encodedHeaders = false)) }
        assertEquals(
            "MAPPING members/93724",
            assertFailsWith<IsuFailure> {
                client.members(letters, 93724, LocalDate.of(2026, 9, 29))
            }.summary(),
        )

        val foreign = loggedIn(client) { Reply(200, "<html><body><table summary=\"Другая таблица\"></table></body></html>") }
        assertEquals(
            "MAPPING members/93724",
            assertFailsWith<IsuFailure> {
                client.members(foreign, 93724, LocalDate.of(2026, 9, 29))
            }.summary(),
        )
    }

    @Test fun `a redirect to the identity realm loses the session`() {
        val client = client()
        val session = loggedIn(client) { target ->
            if (target.startsWith("/pls/apex/")) {
                Reply(302, location = "$base/auth/realms/itmo/protocol/openid-connect/auth")
            } else {
                Reply(200, "<html><form id=\"kc-form-login\"></form></html>")
            }
        }
        assertEquals(
            "SESSION_LOST members/93724",
            assertFailsWith<IsuFailure> {
                client.members(session, 93724, LocalDate.of(2026, 9, 29))
            }.summary(),
        )

        val outside = loggedIn(client) { target ->
            if (target.startsWith("/pls/apex/f?p=2143:15:")) Reply(302, location = "/portal/start") else Reply(200, "<html></html>")
        }
        assertEquals("SESSION_LOST teachers/93724", assertFailsWith<IsuFailure> { client.teachers(outside, 93724) }.summary())

        val failing = loggedIn(client) { Reply(503) }
        assertEquals("HTTP 503 teachers/93724", assertFailsWith<IsuFailure> { client.teachers(failing, 93724) }.summary())
    }

    @Test fun `a new session number issued by ISU is accepted and kept`() {
        val client = client()
        val session = loggedIn(client) { target ->
            when {
                target.startsWith("/pls/apex/f?p=2143:GR:111:") -> Reply(302, location = target.replace("2143:GR:111:", "2143:GR:222:"))

                target.startsWith("/pls/apex/f?p=2143:GR:222:") -> Reply(200, membersPage(listOf("100001"), encodedHeaders = false))

                target.startsWith(
                    "/pls/apex/f?p=2143:15:222:",
                ) -> Reply(200, "<html><a href=\"f?p=2143:PERS:222::NO::PID:142415\">T</a></html>")

                else -> Reply(404)
            }
        }

        assertEquals(setOf(100001), client.members(session, 93724, LocalDate.of(2026, 9, 29)))
        assertEquals("222", session.sessionId)
        assertEquals(setOf(142415), client.teachers(session, 93724))
    }

    @Suppress("ktlint:standard:max-line-length")
    @Test
    fun `members follow the report pagination to the last page`() {
        val client = client()
        val session = loggedIn(client) { target ->
            when {
                target.startsWith("/pls/apex/f?p=2143:GR:111::NO::") -> Reply(
                    200,
                    membersPage(
                        listOf("100001", "100002"),
                        encodedHeaders = true,
                        pagination = """
                        <a href="f?p=2143:GR:111:pg_R_42:NO&amp;pg_min_row=1&amp;pg_max_rows=2&amp;pg_rows_fetched=2">1</a>
                        <a href="f?p=2143:GR:111:pg_R_42:NO&amp;pg_min_row=3&amp;pg_max_rows=2&amp;pg_rows_fetched=2">2</a>
                        <a href="https://example.invalid/pls/apex/f?p=2143:GR:111:pg_R_42:NO&amp;pg_min_row=5">3</a>
                    """,
                    ),
                )

                target == "/pls/apex/f?p=2143:GR:111:pg_R_42:NO&pg_min_row=3&pg_max_rows=2&pg_rows_fetched=2" -> Reply(
                    200,
                    membersPage(
                        listOf("100003"),
                        encodedHeaders = false,
                        pagination = """<a href="f?p=2143:GR:111:pg_R_42:NO&amp;pg_min_row=1&amp;pg_max_rows=2&amp;pg_rows_fetched=2">1</a>""",
                    ),
                )

                else -> Reply(404)
            }
        }

        assertEquals(setOf(100001, 100002, 100003), client.members(session, 93724, LocalDate.of(2026, 9, 29)))
        assertEquals(2, seen.size, "The foreign host link and the link back to the first page are not followed")
    }

    @Test fun `teachers are the PID links of the flow schedule`() {
        val client = client()
        val session = loggedIn(client) { target ->
            if (target == "/pls/apex/f?p=2143:15:111::NO::SCH,SCH_POTOK_ID,SCH_TYPE,SCH_WEEK,SCH_ID,SCH_FOUND:1,93724,5,2,,TRUE") {
                Reply(
                    200,
                    """
                    <html><body><table>
                      <tr><td><a href="f?p=2143:PERS:111::NO::PID:142415">Преподаватель</a></td></tr>
                      <tr><td><a href="f?p=2143:PERS:111::NO::PID:471029">Преподаватель</a></td></tr>
                      <tr><td><a href="f?p=2143:PERS:111::NO::PID:142415">Преподаватель</a></td></tr>
                      <tr><td><a href="f?p=2143:ROOM:111">Аудитория</a></td></tr>
                    </table></body></html>
                """,
                )
            } else {
                Reply(200, "<html><body>Занятий нет</body></html>")
            }
        }

        assertEquals(setOf(142415, 471029), client.teachers(session, 93724))
        assertEquals(emptySet(), client.teachers(session, 96388))
    }

    @Test fun `operations are paced by the request delay`() {
        val client = client(requestDelay = Duration.ofMillis(200))
        route = { target ->
            when (target) {
                "/pls/apex/f?p=2143:1" -> Reply(302, location = "f?p=2143:1:111")
                "/pls/apex/f?p=2143:1:111" -> Reply(200, "<html></html>")
                else -> Reply(200, "<html></html>")
            }
        }

        val beforeLogin = System.nanoTime()
        val session = client.login("seed-identity-value")
        client.teachers(session, 93724)

        val teachersStart = seen.first { it.target.contains("2143:15:") }.at
        assertTrue(Duration.ofNanos(teachersStart - beforeLogin) >= Duration.ofMillis(200))
    }

    @Test fun `network calls cannot run inside an active transaction`() {
        val client = client()
        val session = IsuSession("111", null, null)
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertFailsWith<IllegalStateException> { client.login("seed-identity-value") }
            assertFailsWith<IllegalStateException> { client.members(session, 93724, LocalDate.of(2026, 9, 29)) }
            assertFailsWith<IllegalStateException> { client.teachers(session, 93724) }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        assertTrue(seen.isEmpty())
    }

    @Test fun `text forms never contain the cookie`() {
        val session = IsuSession("123456789", "rotated-identity-value", now)
        assertEquals("IsuSession(redacted)", session.toString())
        val config = config()
        assertFalse(config.toString().contains("seed-identity-value"))
        assertTrue(config.toString().contains("keycloakIdentity=redacted"))
        assertTrue(IsuConfig().toString().contains("keycloakIdentity=none"))
    }

    @Test fun `only an expired identity maps to the expired credential status`() {
        assertEquals(ServiceCredentialStatus.EXPIRED, IsuFailure(IsuErrorCategory.EXPIRED, "login").credentialStatus())
        listOf(IsuErrorCategory.SESSION_LOST, IsuErrorCategory.NETWORK, IsuErrorCategory.HTTP, IsuErrorCategory.MAPPING).forEach {
            assertEquals(ServiceCredentialStatus.FAILED, IsuFailure(it, "members/1").credentialStatus(), it.name)
        }
        assertEquals("NETWORK teachers/93724", IsuFailure(IsuErrorCategory.NETWORK, "teachers/93724").summary())
    }

    /** The observed report shape with synthetic numbers; names and groups are placeholders. */
    private fun membersPage(isus: List<String>, encodedHeaders: Boolean, pagination: String = ""): String {
        val header = if (encodedHeaders) "&#x427;&#x41B;&#x412;&#x41A;_&#x418;&#x414;" else "ЧЛВК_ИД"
        val rows = isus.mapIndexed { index, isu ->
            """
            <tr><td class="report_column" headers="НОМЕР" data-row-id="${index + 1}">${index + 1}</td><td class="report_column" headers="$header" data-row-id="${index + 1}">$isu</td><td class="report_column" headers="ФИО"><a href="#">Студент ${index + 1}</a></td><td class="report_column" headers="ГРУППА">X0000</td></tr>
            """.trimIndent()
        }.joinToString("\n")
        return """
            <html><body><div id="report_42_catch"><div id="R42_pagination_top" class="tb-top-pagination clearfix"><table id="R42_pagination_top_data"><tr><td></td></tr></table></div>
            <div class="table-info table-responsive">
            <table class="table table-bordered" id="report_R42" summary="Список потока 29.09.2026">
            <thead><tr><th id="НОМЕР" class="report_header">№ п/п</th><th id="ЧЛВК_ИД" class="report_header">№ таб.</th><th id="ФИО" class="report_header">Ф.И.О.</th><th id="ГРУППА" class="report_header">Группа</th></tr></thead>
            <tbody>
            $rows
            </tbody>
            </table>
            </div>
            <div class="tb-pagination uReportPagination clearfix"><table id="R42_pagination_data"><tr><td>$pagination</td></tr></table></div>
            </div></body></html>
        """.trimIndent()
    }
}
