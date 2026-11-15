package dev.alllexey.itmowidgets.backend.platform.metrics

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionRepository
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientEventService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientEventSignals
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientEventsSlice
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientPollService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionService
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingService
import dev.alllexey.itmowidgets.backend.feature.connections.web.ClientController
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.platform.error.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.platform.http.RequestBodyLimitFilter
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
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The HTTP counters around the real security chain, the body limit and a real controller with the agent long-poll:
 * the route group from the matched pattern, status classes of answers from the controller and from the filters in
 * front of it, unmatched paths, and the long-poll counted once at its async dispatch and never timed.
 */
@WebMvcTest(ClientController::class)
@Import(
    SecurityConfig::class,
    GlobalExceptionHandler::class,
    ClientPollService::class,
    ClientEventSignals::class,
    DailyCounters::class,
    HttpCounterFilterConfig::class,
    HttpCounterFilterTest.Config::class,
)
class HttpCounterFilterTest @Autowired constructor(private val mvc: MockMvc, private val counters: DailyCounters) {
    @TestConfiguration
    class Config {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean fun connectionResolver() = TestConnections.resolver(
            mapOf(TestConnections.AGENT_TOKEN to AGENT, TestConnections.MCP_TOKEN to MCP),
        )
    }

    @MockitoBean private lateinit var verifier: ItmoJwtVerifier

    @MockitoBean private lateinit var users: UserService

    @MockitoBean private lateinit var connections: ConnectionService

    @MockitoBean private lateinit var connectionRows: ConnectionRepository

    @MockitoBean private lateinit var pairings: PairingService

    @MockitoBean private lateinit var events: ClientEventService

    @MockitoBean private lateinit var transactions: PlatformTransactionManager

    @BeforeEach
    fun reset() {
        `when`(transactions.getTransaction(nullable(TransactionDefinition::class.java))).thenReturn(SimpleTransactionStatus())
        counters.drain()
    }

    @Test
    fun `answers of the controller count by the route group and status class`() {
        perform(put("/api/client/status").agent().json("""{"itmo":"ok"}"""), 200)
        // The mocked service answers no connection: a 500 from the handler.
        perform(get("/api/client/me").agent(), 500)
        perform(get("/api/client/events?after=-1").agent(), 400)

        assertEquals(mapOf("2xx" to 1L, "4xx" to 1L, "5xx" to 1L), requests("client"))
        assertEquals(3L, timed("client"))
    }

    @Test
    fun `answers of the security chain and the body limit keep the group of the route they were for`() {
        perform(get("/api/client/events"), 401)
        perform(get("/api/client/events").header("Authorization", "Bearer ${TestConnections.MCP_TOKEN}"), 403)
        perform(put("/api/client/status").agent().content(ByteArray(RequestBodyLimitFilter.MAX_BYTES.toInt() + 1)), 413)

        assertEquals(mapOf("4xx" to 3L), requests("client"))
        assertEquals(3L, timed("client"))
    }

    @Test
    fun `a path no route matches is other and its segments are never stored`() {
        perform(get("/api/client/963301"), 401)
        perform(get("/api/nowhere/963301").agent(), 403)
        // A route with another method is no match either.
        perform(delete("/api/client/me"), 401)

        val counted = counters.drain()
        assertEquals(mapOf(HttpCounters.OTHER to 3L), counted.filterKeys { it.metric == HttpCounters.HTTP }.mapKeys { it.key.dim1 })
        assertTrue(counted.keys.none { "963301" in it.dim1 || "nowhere" in it.dim1 || it.dim1 == "client" })
    }

    @Test
    fun `the long-poll counts once with its final status at the async dispatch and is not timed`() {
        `when`(events.slice(USER_ID, 3, ConnectionScope.SPORT.eventTypes)).thenReturn(ClientEventsSlice(emptyList(), 3, false, false))
        val parked = mvc.perform(get("/api/client/events?after=3&wait=0&itmo=ok").agent())
            .andExpect(request().asyncStarted())
            .andReturn()
        assertTrue(counters.drain().isEmpty())

        mvc.perform(asyncDispatch(parked)).andExpect(status().isOk)

        val counted = counters.drain()
        assertEquals(mapOf(DailyCounterKey(DAY, HttpCounters.HTTP, "client", "2xx") to 1L), counted)
    }

    @Test
    fun `the group is the literal first segment after api of the pattern`() {
        assertEquals("users", HttpCounterFilter.groupOf("/api/users/{isu}"))
        assertEquals("admin", HttpCounterFilter.groupOf("/api/admin/stats/server"))
        assertEquals("app", HttpCounterFilter.groupOf("/api/app"))
        assertNull(HttpCounterFilter.groupOf("/api/{group}/x"))
        assertNull(HttpCounterFilter.groupOf("/api/**"))
        assertNull(HttpCounterFilter.groupOf("/**"))
        assertNull(HttpCounterFilter.groupOf("/error"))
        assertEquals("2xx", HttpCounters.statusClass(204))
        assertEquals("5xx", HttpCounters.statusClass(503))
        assertNull(HttpCounters.statusClass(101))
    }

    private fun perform(builder: MockHttpServletRequestBuilder, expected: Int) {
        mvc.perform(builder).andExpect(status().`is`(expected))
    }

    private fun MockHttpServletRequestBuilder.agent() = header("Authorization", "Bearer ${TestConnections.AGENT_TOKEN}")

    private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

    private fun requests(group: String): Map<String, Long> =
        pending().filterKeys { it.metric == HttpCounters.HTTP && it.dim1 == group }.mapKeys { it.key.dim2 }

    private fun timed(group: String): Long = pending().filterKeys { it.metric == HttpCounters.HTTP_MS && it.dim1 == group }.values.sum()

    /** Drains once per test and keeps the result, so both helpers read the same counts. */
    private var drained: Map<DailyCounterKey, Long>? = null

    private fun pending(): Map<DailyCounterKey, Long> = drained ?: counters.drain().also {
        assertTrue(it.keys.all { key -> key.day == DAY })
        drained = it
    }

    private companion object {
        val USER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000970201")
        val NOW: Instant = Instant.parse("2026-11-15T09:00:00Z")
        val DAY: LocalDate = LocalDate.parse("2026-11-15")
        val AGENT = TestConnections.client(USER_ID, ClientType.AGENT, setOf(ConnectionScope.SPORT))
        val MCP = TestConnections.client(USER_ID, ClientType.MCP, setOf(ConnectionScope.REVIEWS_READ))
    }
}
