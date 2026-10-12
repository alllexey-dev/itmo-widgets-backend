package dev.alllexey.itmowidgets.backend.feature.agent.web

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentScope
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentTokenEntity
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentTokenOrigin
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentAccessService
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentPollService
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentRoutePolicy
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentTokenService
import dev.alllexey.itmowidgets.backend.feature.agent.service.IssuedAgentToken
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.feature.sport.service.SportAgentBookedNotificationService
import dev.alllexey.itmowidgets.backend.feature.sport.service.SportFreeSignService
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportFreeSignController
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.service.AccountDeletionService
import dev.alllexey.itmowidgets.backend.feature.users.service.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserProfileService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.users.web.UserController
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.ActiveWebSession
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebLoginService
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
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
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

/**
 * The real filters and route allow-list with a resolver that knows two synthetic agent tokens; services are mocks.
 * An agent token reaches only its listed routes, and only agent tokens reach the agent's own routes.
 */
@WebMvcTest(AgentController::class, UserController::class, SportFreeSignController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class, AgentControllerSecurityTest.Config::class)
class AgentControllerSecurityTest @Autowired constructor(private val mvc: MockMvc) {
    @TestConfiguration
    class Config {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        /** Resolves the two tokens below and applies the production allow-list; primary over the mocked token service. */
        @Bean
        @Primary
        fun testAgentResolver() = object : AgentTokenResolver {
            override fun resolve(token: String) = AGENTS[token]

            override fun decide(authentication: AgentAuthentication, method: String, path: String) =
                if (AgentRoutePolicy.permitted(authentication.scopes, method, path) != null) AgentDecision.ALLOWED else AgentDecision.DENIED
        }
    }

    @MockitoBean private lateinit var verifier: ItmoJwtVerifier

    @MockitoBean private lateinit var users: UserService

    @MockitoBean private lateinit var webSessions: WebSessionService

    @MockitoBean private lateinit var webLogins: WebLoginService

    @MockitoBean private lateinit var accountDeletion: AccountDeletionService

    @MockitoBean private lateinit var access: AdminAccess

    @MockitoBean private lateinit var privacy: UserPrivacyService

    @MockitoBean private lateinit var currentGroups: CurrentStudyGroupsService

    @MockitoBean private lateinit var profiles: UserProfileService

    @MockitoBean private lateinit var restrictions: RestrictionService

    @MockitoBean private lateinit var tokens: AgentTokenService

    @MockitoBean private lateinit var agentAccess: AgentAccessService

    @MockitoBean private lateinit var polls: AgentPollService

    @MockitoBean private lateinit var freeSign: SportFreeSignService

    @MockitoBean private lateinit var booked: SportAgentBookedNotificationService

    private val user = User(id = USER_ID, isu = ISU, pictureUrl = null, name = "Synthetic user", createdAt = NOW)

    @BeforeEach
    fun fixture() {
        `when`(verifier.verifyAccessToken(BEARER)).thenReturn(JWT.decode(BEARER))
        `when`(users.resolveIdByIsu(ISU)).thenReturn(USER_ID)
        `when`(users.findUserById(USER_ID)).thenReturn(user)
        `when`(webSessions.resolve(SESSION)).thenReturn(ActiveWebSession(USER_ID, signedInAt = NOW, freshForAdmin = true))
        `when`(
            webSessions.resolve(OLD_SESSION),
        ).thenReturn(ActiveWebSession(USER_ID, signedInAt = NOW.minusSeconds(13 * 3600), freshForAdmin = false))
    }

    @Test
    fun `an agent token reads its identity and reports a booking that pushes to the phone once`() {
        mvc.perform(get("/api/agent/me").agent(SPORT_TOKEN)).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.isu").value(ISU))
            .andExpect(jsonPath("$.data.tokenId").value(SPORT_AGENT.tokenId.toString()))
            .andExpect(jsonPath("$.data.name").value("nas"))
            .andExpect(jsonPath("$.data.scopes[0]").value("sport"))

        `when`(freeSign.markEntrySatisfiedByLesson(USER_ID, 42)).thenReturn(true, false)
        mvc.perform(post("/api/sport/free-sign/lesson/42/mark-satisfied").agent(SPORT_TOKEN)).andExpect(status().isOk)
        mvc.perform(post("/api/sport/free-sign/lesson/42/mark-satisfied").agent(SPORT_TOKEN)).andExpect(status().isOk)
        verify(booked).notify(USER_ID, 42, "nas")
    }

    @Test
    fun `the app's own booking report pushes nothing`() {
        `when`(freeSign.markEntrySatisfiedByLesson(USER_ID, 42)).thenReturn(true)
        mvc.perform(post("/api/sport/free-sign/lesson/42/mark-satisfied").bearer()).andExpect(status().isOk)
        verifyNoInteractions(booked)
    }

    @Test
    fun `an agent token is denied every route outside its list before any service`() {
        for (request in listOf(
            delete("/api/users/me"),
            put("/api/users/me/privacy").contentType(MediaType.APPLICATION_JSON).content("{}"),
            get("/api/users/me/web-login/ABCD2345"),
            post("/api/users/me/web-login/${UUID.randomUUID()}/approve"),
            get("/api/users/me/data"),
            get("/api/agent/access"),
            post("/api/agent/tokens").contentType(MediaType.APPLICATION_JSON).content("""{"name":"x","scopes":["sport"]}"""),
            post("/api/sport/free-sign/entry/create").contentType(MediaType.APPLICATION_JSON).content("{}"),
        )) {
            mvc.perform(
                request.agent(SPORT_TOKEN),
            ).andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("access_denied"))
        }
        // A listed route without its scope.
        mvc.perform(get("/api/sport/free-sign/entry/my").agent(FRIENDS_TOKEN)).andExpect(status().isForbidden)
        verifyNoInteractions(accountDeletion, webLogins, tokens, agentAccess, freeSign, booked, privacy, profiles)
    }

    @Test
    fun `unknown agent tokens are anonymous and never fall back to the cookie`() {
        mvc.perform(get("/api/agent/me")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/agent/me").agent("iwa_unknown")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/users/me/data").agent("iwa_unknown").cookie(Cookie("iw_session", SESSION))).andExpect(status().isUnauthorized)
    }

    @Test
    fun `only agent tokens use the agent routes, only the app and the web use the owner routes`() {
        mvc.perform(
            get("/api/agent/me").bearer(),
        ).andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("access_denied"))
        mvc.perform(get("/api/agent/events").bearer()).andExpect(status().isForbidden)
        `when`(agentAccess.isEnabled(USER_ID)).thenReturn(true)
        mvc.perform(get("/api/agent/access").bearer()).andExpect(status().isOk).andExpect(jsonPath("$.data.enabled").value(true))
        mvc.perform(get("/api/agent/access").cookie(Cookie("iw_session", SESSION))).andExpect(status().isOk)
        verifyNoInteractions(polls)
    }

    @Test
    fun `manual tokens come from the app or a web session signed in within 12 hours`() {
        val issued = IssuedAgentToken(
            "iwa_" + "B".repeat(43),
            AgentTokenEntity(
                userId = USER_ID,
                name = "nas",
                tokenHash = "0".repeat(64),
                tokenHint = "BBBBBBBB",
                scopes = "sport",
                createdVia = AgentTokenOrigin.APP,
                createdAt = NOW,
            ),
        )
        `when`(tokens.issue(USER_ID, "nas", setOf(AgentScope.SPORT), AgentTokenOrigin.APP)).thenReturn(issued)
        `when`(tokens.issue(USER_ID, "nas", setOf(AgentScope.SPORT), AgentTokenOrigin.WEB)).thenReturn(issued)
        val body = """{"name":"nas","scopes":["sport"]}"""

        mvc.perform(post("/api/agent/tokens").bearer().contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.token").value(issued.token))
            .andExpect(jsonPath("$.data.info.scopes[0]").value("sport"))
        mvc.perform(post("/api/agent/tokens").web(SESSION).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk)
        mvc.perform(post("/api/agent/tokens").web(OLD_SESSION).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("recent_sign_in_required"))
        mvc.perform(
            post("/api/agent/tokens").bearer().contentType(MediaType.APPLICATION_JSON).content("""{"name":"nas","scopes":["root"]}"""),
        )
            .andExpect(status().isBadRequest)
        verify(tokens).issue(USER_ID, "nas", setOf(AgentScope.SPORT), AgentTokenOrigin.WEB)
    }

    private fun MockHttpServletRequestBuilder.agent(token: String) = header("Authorization", "Bearer $token")

    private fun MockHttpServletRequestBuilder.bearer() = header("Authorization", "Bearer $BEARER")

    private fun MockHttpServletRequestBuilder.web(session: String) = cookie(Cookie("iw_session", session)).header("X-Web-Request", "1")

    private companion object {
        const val ISU = 970101
        val USER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000970101")
        val NOW: Instant = Instant.parse("2026-10-12T09:00:00Z")
        val BEARER: String = JWT.create().withClaim("isu", ISU).sign(Algorithm.none())
        const val SESSION = "synthetic-session-token"
        const val OLD_SESSION = "synthetic-old-session-token"
        const val SPORT_TOKEN = "iwa_synthetic-sport"
        const val FRIENDS_TOKEN = "iwa_synthetic-friends"
        val SPORT_AGENT = AgentAuthentication(USER_ID, UUID.randomUUID(), "nas", setOf("sport"))
        val AGENTS = mapOf(
            SPORT_TOKEN to SPORT_AGENT,
            FRIENDS_TOKEN to AgentAuthentication(USER_ID, UUID.randomUUID(), "pi", setOf("friends:read")),
        )
    }
}
