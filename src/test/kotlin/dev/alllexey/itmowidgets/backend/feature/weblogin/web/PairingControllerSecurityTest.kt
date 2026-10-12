package dev.alllexey.itmowidgets.backend.feature.weblogin.web

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentRoutePolicy
import dev.alllexey.itmowidgets.backend.feature.agent.web.AgentPairingController
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.weblogin.model.PairingKind
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.ActiveWebSession
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.ConnectionNotificationService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.ConnectionsService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.NewPairing
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.PairingClaim
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.PairingService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.PairingView
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.platform.error.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.platform.security.AgentAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.AgentDecision
import dev.alllexey.itmowidgets.backend.platform.security.AgentTokenResolver
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The real filters and allow-list: who reaches each pairing and connections route; services are mocks. */
@WebMvcTest(AgentPairingController::class, PairingController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class, PairingControllerSecurityTest.Config::class)
class PairingControllerSecurityTest @Autowired constructor(private val mvc: MockMvc) {
    @TestConfiguration
    class Config {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean
        fun testAgentResolver() = object : AgentTokenResolver {
            override fun resolve(token: String) = AGENT.takeIf { token == AGENT_TOKEN }

            override fun decide(authentication: AgentAuthentication, method: String, path: String) =
                if (AgentRoutePolicy.permitted(authentication.scopes, method, path) != null) AgentDecision.ALLOWED else AgentDecision.DENIED
        }
    }

    @MockitoBean private lateinit var verifier: ItmoJwtVerifier

    @MockitoBean private lateinit var users: UserService

    @MockitoBean private lateinit var webSessions: WebSessionService

    @MockitoBean private lateinit var pairings: PairingService

    @MockitoBean private lateinit var connections: ConnectionsService

    @MockitoBean private lateinit var notifications: ConnectionNotificationService

    private val challengeId = UUID.randomUUID()

    @BeforeEach
    fun fixture() {
        `when`(verifier.verifyAccessToken(BEARER)).thenReturn(JWT.decode(BEARER))
        `when`(users.resolveIdByIsu(ISU)).thenReturn(USER_ID)
        `when`(webSessions.resolve(SESSION)).thenReturn(ActiveWebSession(USER_ID, NOW, freshForAdmin = true, sessionId = SESSION_ID))
    }

    @Test
    fun `a target creates and polls anonymously, and the claiming poll pushes to the owner once`() {
        val commitment = PairingService.encode(ByteArray(32))
        `when`(pairings.create(PairingKind.AGENT, commitment, "nas", null, "agent/1.0", "127.0.0.1"))
            .thenReturn(NewPairing(challengeId, "ABCD2345", "secret", NOW))
        mvc.perform(
            post("/api/pair/challenges").header("User-Agent", "agent/1.0").contentType(MediaType.APPLICATION_JSON)
                .content("""{"kind":"AGENT","keyCommitment":"$commitment","name":"nas"}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.data.code").value("ABCD2345"))

        `when`(pairings.poll(challengeId, "secret")).thenReturn(
            PairingClaim.Opened,
            PairingClaim.Approved(challengeId, AGENT.tokenId, USER_ID, PairingKind.AGENT_SESSION, "nas", ByteArray(48), null),
        )
        mvc.perform(get("/api/pair/challenges/$challengeId").header("X-Poll-Secret", "secret"))
            .andExpect(
                status().isOk,
            ).andExpect(jsonPath("$.data.status").value("OPENED")).andExpect(jsonPath("$.data.sealedBundle").isEmpty)
        mvc.perform(get("/api/pair/challenges/$challengeId").header("X-Poll-Secret", "secret"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.status").value("APPROVED"))
            .andExpect(jsonPath("$.data.sealedBundle").value(PairingService.encode(ByteArray(48))))
        verify(notifications).notifyConnected(USER_ID, AGENT.tokenId, PairingKind.AGENT_SESSION, "nas", null)
        mvc.perform(get("/api/pair/challenges/$challengeId")).andExpect(status().isBadRequest)
    }

    @Test
    fun `only the app's bearer opens, approves and rejects, the cookie is ignored and agent tokens are denied`() {
        `when`(pairings.open(USER_ID, "ABCD2345")).thenReturn(
            PairingView(challengeId, PairingKind.AGENT, "nas", null, null, ByteArray(32), null, NOW, NOW),
        )
        mvc.perform(post("/api/users/me/pair/ABCD2345/open").bearer()).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.keyCommitment").value(PairingService.encode(ByteArray(32))))
        mvc.perform(post("/api/users/me/pair/$challengeId/reject").bearer()).andExpect(status().isOk)
        verify(pairings).reject(USER_ID, challengeId)

        // Builders are mutable: each call gets a fresh one.
        for (request in listOf(
            { post("/api/users/me/pair/ABCD2345/open") },
            { post("/api/users/me/pair/$challengeId/approve").contentType(MediaType.APPLICATION_JSON).content("{}") },
            { post("/api/users/me/pair/$challengeId/reject") },
        )) {
            mvc.perform(request().web()).andExpect(status().isUnauthorized)
            mvc.perform(request().agent()).andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("access_denied"))
            mvc.perform(request()).andExpect(status().isUnauthorized)
        }
        verifyNoInteractions(webSessions)
    }

    @Test
    fun `connections are listed and revoked by the app or the web, never by an agent`() {
        `when`(connections.list(USER_ID, SESSION_ID)).thenReturn(emptyList())
        mvc.perform(get("/api/users/me/connections").web()).andExpect(status().isOk)
        mvc.perform(get("/api/users/me/connections").bearer()).andExpect(status().isOk)
        verify(connections).list(USER_ID, null)
        mvc.perform(post("/api/users/me/connections/$challengeId/revoke").web()).andExpect(status().isOk)
        mvc.perform(post("/api/users/me/connections/$challengeId/revoke").cookie(Cookie("iw_session", SESSION)))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("csrf"))
        mvc.perform(get("/api/users/me/connections").agent()).andExpect(status().isForbidden)
        mvc.perform(post("/api/users/me/connections/$challengeId/revoke").agent()).andExpect(status().isForbidden)
        mvc.perform(get("/api/users/me/connections")).andExpect(status().isUnauthorized)
        verify(connections).revoke(USER_ID, challengeId)
    }

    @Test
    fun `a session hand-off is created only with an agent token`() {
        val body = """{"keyCommitment":"${PairingService.encode(ByteArray(32))}"}"""
        `when`(pairings.createSession(AGENT.tokenId, PairingService.encode(ByteArray(32)), "127.0.0.1"))
            .thenReturn(NewPairing(challengeId, "ABCD2345", "secret", NOW))
        mvc.perform(post("/api/agent/pair/session").agent().contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk)
        mvc.perform(post("/api/agent/pair/session").bearer().contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden)
        mvc.perform(
            post("/api/agent/pair/session").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isUnauthorized)
    }

    private fun MockHttpServletRequestBuilder.bearer() = header("Authorization", "Bearer $BEARER")

    private fun MockHttpServletRequestBuilder.agent() = header("Authorization", "Bearer $AGENT_TOKEN")

    private fun MockHttpServletRequestBuilder.web() = cookie(Cookie("iw_session", SESSION)).header("X-Web-Request", "1")

    private companion object {
        const val ISU = 970201
        val USER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000970201")
        val SESSION_ID: UUID = UUID.randomUUID()
        val NOW: Instant = Instant.parse("2026-10-12T09:00:00Z")
        val BEARER: String = JWT.create().withClaim("isu", ISU).sign(Algorithm.none())
        const val SESSION = "synthetic-session-token"
        const val AGENT_TOKEN = "iwa_synthetic"
        val AGENT = AgentAuthentication(USER_ID, UUID.randomUUID(), "nas", setOf("sport"))
    }
}
