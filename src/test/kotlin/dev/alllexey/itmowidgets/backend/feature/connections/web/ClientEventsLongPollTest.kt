package dev.alllexey.itmowidgets.backend.feature.connections.web

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientEventRow
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionRepository
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientEventService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientEventSignals
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientEventsSlice
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientPollService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionService
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.platform.error.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import dev.alllexey.itmowidgets.backend.testing.TestConnections
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The parked `/events` poll answers on another thread through an async dispatch; the caller must still be authorized
 * there, or an agent reads the 401 as a revoked token. Real poll service and signals, mocked event log.
 */
@WebMvcTest(ClientController::class)
@Import(
    SecurityConfig::class,
    GlobalExceptionHandler::class,
    ClientPollService::class,
    ClientEventSignals::class,
    ClientEventsLongPollTest.Config::class,
)
class ClientEventsLongPollTest @Autowired constructor(private val mvc: MockMvc, private val signals: ClientEventSignals) {
    @TestConfiguration
    class Config {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean fun connectionResolver() = TestConnections.resolver(mapOf(TestConnections.AGENT_TOKEN to AGENT))
    }

    @MockitoBean private lateinit var verifier: ItmoJwtVerifier

    @MockitoBean private lateinit var users: UserService

    @MockitoBean private lateinit var connections: ConnectionService

    @MockitoBean private lateinit var connectionRows: ConnectionRepository

    @MockitoBean private lateinit var pairings: PairingService

    @MockitoBean private lateinit var events: ClientEventService

    @MockitoBean private lateinit var transactions: PlatformTransactionManager

    @BeforeEach
    fun transactions() {
        `when`(transactions.getTransaction(nullable(TransactionDefinition::class.java))).thenReturn(SimpleTransactionStatus())
    }

    @Test
    fun `an empty long-poll answers 200 with an empty page after its wait`() {
        `when`(events.slice(USER_ID, 3, TYPES)).thenReturn(EMPTY)
        val parked = park()

        mvc.perform(asyncDispatch(parked)).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.events").isEmpty)
            .andExpect(jsonPath("$.data.nextCursor").value(3))
    }

    @Test
    fun `waiting events answer at once, still through the async dispatch`() {
        `when`(events.slice(USER_ID, 3, TYPES)).thenReturn(ClientEventsSlice(listOf(EVENT), 4, hasMore = false, gap = false))

        mvc.perform(asyncDispatch(park(wait = 0))).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.events[0].seq").value(4))
    }

    @Test
    fun `a long-poll woken by an append answers the event`() {
        `when`(events.slice(USER_ID, 3, TYPES)).thenReturn(EMPTY)
        val parked = park(wait = 30)
        `when`(events.slice(USER_ID, 3, TYPES)).thenReturn(ClientEventsSlice(listOf(EVENT), 4, hasMore = false, gap = false))
        signals.wake(USER_ID)

        mvc.perform(asyncDispatch(parked)).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.events[0].seq").value(4))
            .andExpect(jsonPath("$.data.events[0].id").value(EVENT.dedupKey))
            .andExpect(jsonPath("$.data.nextCursor").value(4))
    }

    /** asyncDispatch waits for the parked poll's answer, then replays the response as the container would. */
    private fun park(wait: Int = 1): MvcResult =
        mvc.perform(get("/api/client/events?after=3&wait=$wait&itmo=ok").header("Authorization", "Bearer ${TestConnections.AGENT_TOKEN}"))
            .andExpect(request().asyncStarted())
            .andReturn()

    private companion object {
        val USER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000970101")
        val NOW: Instant = Instant.parse("2026-10-12T22:40:42Z")
        val AGENT = TestConnections.client(USER_ID, ClientType.AGENT, setOf(ConnectionScope.SPORT))
        val TYPES = ConnectionScope.SPORT.eventTypes
        val EMPTY = ClientEventsSlice(emptyList(), 3, hasMore = false, gap = false)
        val EVENT = ClientEventRow(4, "sport-free:7:1", TYPES.first(), "{}", NOW, NOW.plusSeconds(3600))
    }
}
