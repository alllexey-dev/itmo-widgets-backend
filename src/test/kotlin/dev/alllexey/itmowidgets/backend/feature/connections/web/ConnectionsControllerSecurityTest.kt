package dev.alllexey.itmowidgets.backend.feature.connections.web

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.clients.service.ClientActivityService
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.model.ItmoStatus
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairKind
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientPollService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionNotificationService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionService
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingClaim
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.feature.users.service.AccountDeletionService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserProfileService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.users.web.UserController
import dev.alllexey.itmowidgets.backend.platform.error.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.platform.security.BearerAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.ConnectionAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.principal
import dev.alllexey.itmowidgets.backend.testing.TestConnections
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.endsWith
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Every connections route with the real connection filter and route policy: the allowed callers and the denied ones
 * (anonymous, the app's bearer where only a connection belongs, a connection where only the phone belongs).
 */
@WebMvcTest(PairingController::class, ClientController::class, ConnectionsController::class, UserController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class, ConnectionsControllerSecurityTest.Config::class)
class ConnectionsControllerSecurityTest @Autowired constructor(private val mvc: MockMvc) {
    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean fun connectionResolver() = TestConnections.resolver(
            mapOf(
                TestConnections.WEB_TOKEN to WEB,
                TestConnections.AGENT_TOKEN to AGENT,
                TestConnections.MCP_TOKEN to MCP,
            ),
        )
    }

    @MockitoBean private lateinit var jwt: JwtAuthFilter

    @MockitoBean private lateinit var pairings: PairingService

    @MockitoBean private lateinit var connections: ConnectionService

    @MockitoBean private lateinit var polls: ClientPollService

    @MockitoBean private lateinit var notifications: ConnectionNotificationService

    @MockitoBean private lateinit var users: UserService

    @MockitoBean private lateinit var privacy: UserPrivacyService

    @MockitoBean private lateinit var access: AdminAccess

    @MockitoBean private lateinit var profiles: UserProfileService

    @MockitoBean private lateinit var restrictions: RestrictionService

    @MockitoBean private lateinit var accountDeletion: AccountDeletionService

    @MockitoBean private lateinit var clients: ClientActivityService

    @BeforeEach
    fun passRequestsThroughJwtFilterMock() {
        doAnswer {
            it.getArgument<FilterChain>(2).doFilter(it.getArgument(0), it.getArgument(1))
            null
        }.`when`(jwt).doFilter(any(), any(), any())
    }

    @Test
    fun `a client creates and claims a challenge anonymously, and the web gets its token only as a cookie`() {
        val created = CreatedChallenge(CHALLENGE, "K7Q2M9AB", "s".repeat(43), NOW.plusSeconds(120))
        `when`(pairings.create(createRequest(), "127.0.0.1")).thenReturn(created)
        mvc.perform(post("/api/pair/challenges").json(CREATE)).andExpect(status().isOk).andExpect(jsonPath("$.data.code").value("K7Q2M9AB"))

        `when`(pairings.claim(CHALLENGE, CLAIM)).thenReturn(PairingClaim.Waiting(opened = true))
        mvc.perform(post("/api/pair/challenges/$CHALLENGE/claim").json("""{"claimSecret":"$CLAIM"}"""))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.data.status").value("OPENED"))

        `when`(
            pairings.claim(CHALLENGE, CLAIM),
        ).thenReturn(PairingClaim.Claimed(USER_ID, PairKind.CONNECT, TOKEN, info(ClientType.WEB), null))
        mvc.perform(post("/api/pair/challenges/$CHALLENGE/claim").json("""{"claimSecret":"$CLAIM"}""").cookie(Cookie("iw_client", "stale")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("CLAIMED"))
            .andExpect(jsonPath("$.data.token").doesNotExist())
            .andExpect(
                header().string(
                    HttpHeaders.SET_COOKIE,
                    allOf(startsWith("iw_client=$TOKEN; Path=/api; Max-Age=34560000;"), endsWith(COOKIE_FLAGS)),
                ),
            )
        verify(notifications).notifyConnected(USER_ID, CONNECTION, ClientType.WEB, "Chrome, macOS")

        `when`(
            pairings.claim(CHALLENGE, CLAIM),
        ).thenReturn(PairingClaim.Claimed(USER_ID, PairKind.RENEW, null, info(ClientType.AGENT), ByteArray(48)))
        mvc.perform(post("/api/pair/challenges/$CHALLENGE/claim").json("""{"claimSecret":"$CLAIM"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.sealedBundle").isString)
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
    }

    @Test
    fun `only the app's bearer opens and approves a pairing`() {
        // A request builder keeps what `with` adds, so every call builds its own.
        val routes = listOf({ post("/api/users/me/pair/K7Q2M9AB/open") }, { post("/api/users/me/pair/$CHALLENGE/approve").json("{}") })
        `when`(pairings.approve(USER_ID, CHALLENGE, ApprovePairingRequest(null, null, null))).thenReturn(CONNECTION)
        routes.forEach { mvc.perform(it().with(authentication(phone()))).andExpect(status().isOk) }
        routes.forEach { mvc.perform(it()).andExpect(status().isUnauthorized) }
        for (caller in listOf(::asWeb, ::asAgent, ::asMcp)) {
            routes.forEach {
                mvc.perform(caller(it())).andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("access_denied"))
            }
        }
        verify(pairings).open(USER_ID, "K7Q2M9AB")
    }

    @Test
    fun `devices, revoke, narrowing and install sign-out are the app's and the web's, itmo-ended the app's alone`() {
        mvc.perform(
            get("/api/users/me/devices").with(authentication(phone())).header("X-Client", "kind=ios; version=2.4.0; install=$INSTALL"),
        )
            .andExpect(status().isOk)
        verify(connections).devices(USER_ID, INSTALL)
        mvc.perform(
            asWeb(get("/api/users/me/devices")).header("X-Client", "kind=web; version=abc; install=$INSTALL"),
        ).andExpect(status().isOk)
        verify(connections).devices(USER_ID, null)

        mvc.perform(put("/api/users/me/connections/$CONNECTION/scopes").json(SCOPES).with(authentication(phone()))).andExpect(status().isOk)
        verify(connections).updateScopes(USER_ID, CONNECTION, setOf(ConnectionScope.SPORT), true)
        mvc.perform(asWeb(put("/api/users/me/connections/$CONNECTION/scopes").json(SCOPES))).andExpect(status().isOk)
        verify(connections).updateScopes(USER_ID, CONNECTION, setOf(ConnectionScope.SPORT), false)

        mvc.perform(asWeb(post("/api/users/me/connections/$CONNECTION/revoke"))).andExpect(status().isOk)
        mvc.perform(asWeb(post("/api/users/me/installs/$INSTALL/sign-out"))).andExpect(status().isOk)
        verify(connections).signOutInstall(USER_ID, INSTALL, null)

        mvc.perform(post("/api/users/me/connections/$CONNECTION/itmo-ended").with(authentication(phone()))).andExpect(status().isOk)
        mvc.perform(asWeb(post("/api/users/me/connections/$CONNECTION/itmo-ended"))).andExpect(status().isForbidden)
        verify(connections).itmoEnded(USER_ID, CONNECTION)

        val routes = listOf(
            { get("/api/users/me/devices") },
            { put("/api/users/me/connections/$CONNECTION/scopes").json(SCOPES) },
            { post("/api/users/me/connections/$CONNECTION/revoke") },
            { post("/api/users/me/installs/$INSTALL/sign-out") },
            { post("/api/users/me/connections/$CONNECTION/itmo-ended") },
        )
        routes.forEach { mvc.perform(it()).andExpect(status().isUnauthorized) }
        for (caller in listOf(::asAgent, ::asMcp)) routes.forEach { mvc.perform(caller(it())).andExpect(status().isForbidden) }
    }

    @Test
    fun `cookie changes need the web header`() {
        mvc.perform(post("/api/users/me/connections/$CONNECTION/revoke").cookie(Cookie("iw_client", TestConnections.WEB_TOKEN)))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("csrf"))
        verify(connections, never()).revoke(USER_ID, CONNECTION)
    }

    @Test
    fun `consent comes from the app alone`() {
        mvc.perform(put("/api/users/me/consent").json("""{"version":2}""").with(authentication(phone()))).andExpect(status().isOk)
        verify(users).enroll(USER_ID, 2)
        mvc.perform(put("/api/users/me/consent").json("""{"version":0}""").with(authentication(phone()))).andExpect(status().isBadRequest)
        mvc.perform(asWeb(put("/api/users/me/consent").json("""{"version":2}"""))).andExpect(status().isForbidden)
        mvc.perform(put("/api/users/me/consent").json("""{"version":2}""")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `the client routes take a connection, each only of its client types`() {
        `when`(connections.get(CONNECTION)).thenReturn(info(ClientType.AGENT))
        `when`(users.isuOf(USER_ID)).thenReturn(970001)
        for (caller in listOf(::asWeb, ::asAgent, ::asMcp)) mvc.perform(caller(get("/api/client/me"))).andExpect(status().isOk)
        mvc.perform(get("/api/client/me").with(authentication(phone())))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("access_denied"))
        mvc.perform(get("/api/client/me")).andExpect(status().isUnauthorized)

        mvc.perform(asMcp(put("/api/client/status").json("""{"itmo":"closed"}"""))).andExpect(status().isOk)
        verify(connections).reportStatus(CONNECTION, ItmoStatus.CLOSED, null)
        mvc.perform(asWeb(put("/api/client/status").json("""{"itmo":"none"}"""))).andExpect(status().isForbidden)

        mvc.perform(asAgent(post("/api/client/pair/renew").json("""{"keyCommitment":"x"}"""))).andExpect(status().isOk)
        mvc.perform(post("/api/client/pair/renew").json("""{"keyCommitment":"x"}""").with(authentication(phone())))
            .andExpect(status().isForbidden)

        mvc.perform(asMcp(get("/api/client/events"))).andExpect(status().isForbidden)
        mvc.perform(asWeb(get("/api/client/events"))).andExpect(status().isForbidden)
        verifyNoInteractions(polls)

        mvc.perform(asWeb(delete("/api/client/self"))).andExpect(status().isOk)
            .andExpect(
                header().string(HttpHeaders.SET_COOKIE, allOf(startsWith("iw_client=; Path=/api; Max-Age=0;"), endsWith(COOKIE_FLAGS))),
            )
        verify(connections).revoke(USER_ID, CONNECTION)
        mvc.perform(delete("/api/client/self").with(authentication(phone()))).andExpect(status().isForbidden)
    }

    private fun phone() = BearerAuthentication(principal(USER_ID), NOW)

    private fun asWeb(request: MockHttpServletRequestBuilder) =
        request.cookie(Cookie("iw_client", TestConnections.WEB_TOKEN)).header("X-Web-Request", "1")

    private fun asAgent(request: MockHttpServletRequestBuilder) =
        request.header(HttpHeaders.AUTHORIZATION, "Bearer ${TestConnections.AGENT_TOKEN}")

    private fun asMcp(request: MockHttpServletRequestBuilder) =
        request.header(HttpHeaders.AUTHORIZATION, "Bearer ${TestConnections.MCP_TOKEN}")

    private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

    private fun createRequest() = CreateChallengeRequest(PairKind.CONNECT, ClientType.WEB, "x", "Chrome, macOS", "abc")

    private fun info(client: ClientType) = ConnectionInfo(
        CONNECTION, client, "Chrome, macOS", null, null, emptyList(), NOW, NOW, null, false, null, ItmoStatus.NONE, null,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-05T09:00:00Z")
        val USER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000970001")
        val CONNECTION: UUID = UUID.fromString("00000000-0000-4000-8000-000000000601")
        val CHALLENGE: UUID = UUID.fromString("00000000-0000-4000-8000-000000000501")
        val INSTALL: UUID = UUID.fromString("00000000-0000-4000-8000-000000000701")
        const val CLAIM = "cccccccccccccccccccccccccccccccccccccccccccc"
        const val TOKEN = "iwc_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val CREATE = """{"kind":"CONNECT","client":"web","keyCommitment":"x","name":"Chrome, macOS","version":"abc"}"""
        const val SCOPES = """{"scopes":["sport"]}"""
        const val COOKIE_FLAGS = "Secure; HttpOnly; SameSite=Strict"
        val WEB: ConnectionAuthentication = TestConnections.web(USER_ID, NOW, id = CONNECTION)
        val AGENT: ConnectionAuthentication =
            TestConnections.client(USER_ID, ClientType.AGENT, setOf(ConnectionScope.SPORT), id = CONNECTION)
        val MCP: ConnectionAuthentication =
            TestConnections.client(USER_ID, ClientType.MCP, setOf(ConnectionScope.LINKS_READ), id = CONNECTION)
    }
}
