package dev.alllexey.itmowidgets.backend.feature.agent.web

import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentEventRow
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentScope
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentAccessService
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentEventService
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentEventSignals
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentEventsSlice
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentPollService
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentRoutePolicy
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentTokenService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.platform.error.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.platform.security.AgentAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.AgentDecision
import dev.alllexey.itmowidgets.backend.platform.security.AgentTokenResolver
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The parked `/events` poll answers on another thread through an async dispatch; the caller must still be authorized
 * there, or an agent reads the 401 as a revoked token. Real poll service and signals, mocked event log.
 */
@WebMvcTest(AgentController::class)
@Import(
    SecurityConfig::class,
    GlobalExceptionHandler::class,
    AgentPollService::class,
    AgentEventSignals::class,
    AgentEventsLongPollTest.Config::class,
)
class AgentEventsLongPollTest @Autowired constructor(private val mvc: MockMvc, private val signals: AgentEventSignals) {
    @TestConfiguration
    class Config {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean
        @Primary
        fun testAgentResolver() = object : AgentTokenResolver {
            override fun resolve(token: String) = AGENT.takeIf { token == TOKEN }

            override fun decide(authentication: AgentAuthentication, method: String, path: String) =
                if (AgentRoutePolicy.permitted(authentication.scopes, method, path) != null) AgentDecision.ALLOWED else AgentDecision.DENIED
        }
    }

    @MockitoBean private lateinit var verifier: ItmoJwtVerifier

    @MockitoBean private lateinit var users: UserService

    @MockitoBean private lateinit var webSessions: WebSessionService

    @MockitoBean private lateinit var tokens: AgentTokenService

    @MockitoBean private lateinit var agentAccess: AgentAccessService

    @MockitoBean private lateinit var events: AgentEventService

    @Test
    fun `an empty long-poll answers 200 with an empty page after its wait`() {
        `when`(events.slice(USER_ID, 3, TYPES)).thenReturn(EMPTY)
        val parked = park()

        mvc.perform(asyncDispatch(parked)).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.events").isEmpty)
            .andExpect(jsonPath("$.data.nextCursor").value(3))
    }

    @Test
    fun `a long-poll woken by an append answers the event`() {
        `when`(events.slice(USER_ID, 3, TYPES)).thenReturn(EMPTY)
        val parked = park(wait = 30)
        `when`(events.slice(USER_ID, 3, TYPES)).thenReturn(AgentEventsSlice(listOf(EVENT), 4, hasMore = false, gap = false))
        signals.wake(USER_ID)

        mvc.perform(asyncDispatch(parked)).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.events[0].seq").value(4))
            .andExpect(jsonPath("$.data.events[0].id").value(EVENT.dedupKey))
            .andExpect(jsonPath("$.data.nextCursor").value(4))
    }

    /** asyncDispatch waits for the parked poll's answer, then replays the response as the container would. */
    private fun park(wait: Int = 1): MvcResult =
        mvc.perform(get("/api/agent/events?after=3&wait=$wait&itmo=ok").header("Authorization", "Bearer $TOKEN"))
            .andExpect(request().asyncStarted())
            .andReturn()

    private companion object {
        val USER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000970101")
        val NOW: Instant = Instant.parse("2026-10-12T22:40:42Z")
        const val TOKEN = "iwa_synthetic-sport"
        val AGENT = AgentAuthentication(USER_ID, UUID.randomUUID(), "nas", setOf("sport"))
        val TYPES = AgentScope.SPORT.eventTypes
        val EMPTY = AgentEventsSlice(emptyList(), 3, hasMore = false, gap = false)
        val EVENT = AgentEventRow(4, "sport-free:7:1", TYPES.first(), "{}", NOW, NOW.plusSeconds(3600))
    }
}
