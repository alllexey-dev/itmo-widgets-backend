package dev.alllexey.itmowidgets.backend.feature.agent.persistence

import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentScope
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentTokenOrigin
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentAccessService
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentConfig
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentEventService
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentEventSignals
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentReachability
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentRetention
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentTokenService
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.QueueTestClock
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.platform.security.AgentDecision
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Agent tokens, the event log and liveness on real PostgreSQL, committed, with a controllable clock. */
@Import(
    AgentTokenService::class,
    AgentAccessService::class,
    AgentEventService::class,
    AgentEventSignals::class,
    AgentEventStore::class,
    AgentReachability::class,
    AgentRetention::class,
    AgentPersistenceTest.Config::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentPersistenceTest : PostgreSqlRepositoryTest() {
    @TestConfiguration
    class Config {
        @Bean fun agentTestClock() = QueueTestClock()

        @Bean fun agentConfig() = AgentConfig()

        @Bean fun agentTestJsonMapper(): JsonMapper = JsonMapper.builder().build()
    }

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var clock: QueueTestClock

    @Autowired private lateinit var tokens: AgentTokenService

    @Autowired private lateinit var access: AgentAccessService

    @Autowired private lateinit var events: AgentEventService

    @Autowired private lateinit var reachability: AgentReachability

    @Autowired private lateinit var retention: AgentRetention

    private val users = mutableListOf<UUID>()

    @BeforeEach
    fun resetClock() = clock.set(NOW)

    @AfterEach
    fun removeUsers() = users.forEach { jdbc.update("DELETE FROM users WHERE id = ?", it) }

    @Test
    fun `a token is stored only as its hash and resolves to its owner and scopes`() {
        val user = user()
        val issued = tokens.issue(user, "  nas  ", setOf(AgentScope.SPORT, AgentScope.FRIENDS_READ), AgentTokenOrigin.APP)

        assertTrue(issued.token.matches(Regex("^iwa_[A-Za-z0-9_-]{43}$")))
        val row = jdbc.queryForMap("SELECT name, token_hash, token_hint, scopes, created_via FROM agent_tokens WHERE user_id = ?", user)
        assertEquals("nas", row["name"])
        assertEquals("sport,friends:read", row["scopes"])
        assertEquals("APP", row["created_via"])
        assertEquals(issued.token.substring(4, 12), row["token_hint"])
        assertFalse(row.values.any { it.toString().contains(issued.token.substring(4)) })
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM agent_tokens WHERE token_hash = ?", Int::class.java, issued.token))

        val authentication = assertNotNull(tokens.resolve(issued.token))
        assertEquals(user, authentication.userId)
        assertEquals(issued.entity.id, authentication.tokenId)
        assertEquals(setOf("sport", "friends:read"), authentication.scopes)
        val other = if (issued.token.last() == 'A') "B" else "A"
        assertNull(tokens.resolve(issued.token.dropLast(1) + other))
        assertNull(tokens.resolve("iwa_short"))
    }

    @Test
    fun `issuing needs access, a scope, a sane name and a free slot`() {
        val user = user(granted = false)
        assertFailsWith<PermissionDeniedException> { tokens.issue(user, "nas", setOf(AgentScope.SPORT), AgentTokenOrigin.APP) }
        access.setGranted(user, true)
        assertFailsWith<InvalidRequestDataException> { tokens.issue(user, "nas", emptySet(), AgentTokenOrigin.APP) }
        assertFailsWith<InvalidRequestDataException> { tokens.issue(user, " ", setOf(AgentScope.SPORT), AgentTokenOrigin.APP) }
        assertFailsWith<InvalidRequestDataException> { tokens.issue(user, "a\u0007b", setOf(AgentScope.SPORT), AgentTokenOrigin.APP) }
        assertFailsWith<InvalidRequestDataException> { tokens.issue(user, "x".repeat(61), setOf(AgentScope.SPORT), AgentTokenOrigin.APP) }
        repeat(5) { tokens.issue(user, "agent $it", setOf(AgentScope.SPORT), AgentTokenOrigin.PAIR) }
        assertFailsWith<BusinessRuleException> { tokens.issue(user, "sixth", setOf(AgentScope.SPORT), AgentTokenOrigin.APP) }
    }

    @Test
    fun `revoked and idle tokens authenticate nobody, and access off keeps them but disables them`() {
        val user = user()
        val revoked = tokens.issue(user, "old", setOf(AgentScope.SPORT), AgentTokenOrigin.APP)
        assertTrue(tokens.revoke(user, revoked.entity.id))
        assertFalse(tokens.revoke(user, revoked.entity.id))
        assertNull(tokens.resolve(revoked.token))

        val idle = tokens.issue(user, "idle", setOf(AgentScope.SPORT), AgentTokenOrigin.APP)
        clock.advance(Duration.ofDays(89))
        val authentication = assertNotNull(tokens.resolve(idle.token))
        assertEquals(AgentDecision.ALLOWED, tokens.decide(authentication, "GET", "/api/agent/me"))
        access.setGranted(user, false)
        assertEquals(AgentDecision.DISABLED, tokens.decide(authentication, "GET", "/api/agent/me"))
        assertEquals(AgentDecision.DISABLED, tokens.decide(authentication, "GET", "/api/users/me/connections"))
        // Unpairing only takes access away, so it works with access off.
        assertEquals(AgentDecision.ALLOWED, tokens.decide(authentication, "DELETE", "/api/agent/self"))
        access.setGranted(user, true)
        // Use moved the idle window; 90 days after the last use it ends.
        clock.advance(Duration.ofDays(90))
        assertNull(tokens.resolve(idle.token))
        assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM agent_tokens WHERE id = ?", Instant::class.java, idle.entity.id))
    }

    @Test
    fun `the scheduled retention revokes idle tokens`() {
        val user = user()
        val issued = tokens.issue(user, "idle", setOf(AgentScope.SPORT), AgentTokenOrigin.APP)
        clock.advance(Duration.ofDays(91))
        // The cron entry point, not the proxied purge(): a self-call skips @Transactional.
        retention.scheduled()
        assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM agent_tokens WHERE id = ?", Instant::class.java, issued.entity.id))
    }

    @Test
    fun `routes outside the allow-list, a missing scope and a burst are refused`() {
        val user = user()
        val schedule = assertNotNull(tokens.resolve(tokens.issue(user, "s", setOf(AgentScope.SCHEDULE_WRITE), AgentTokenOrigin.APP).token))
        assertEquals(AgentDecision.ALLOWED, tokens.decide(schedule, "POST", "/api/schedule/lessons/sync"))
        assertEquals(AgentDecision.DENIED, tokens.decide(schedule, "GET", "/api/sport/free-sign/entry/my"))
        assertEquals(AgentDecision.DENIED, tokens.decide(schedule, "DELETE", "/api/users/me"))
        repeat(AgentTokenService.ROUTES_PER_MINUTE - 1) { tokens.decide(schedule, "POST", "/api/schedule/lessons/sync") }
        assertEquals(AgentDecision.RATE_LIMITED, tokens.decide(schedule, "POST", "/api/schedule/lessons/sync"))
        // The long-poll has its own budget.
        assertEquals(AgentDecision.ALLOWED, tokens.decide(schedule, "GET", "/api/agent/events"))
        clock.advance(Duration.ofMinutes(1))
        assertEquals(AgentDecision.ALLOWED, tokens.decide(schedule, "POST", "/api/schedule/lessons/sync"))
    }

    @Test
    fun `concurrent appends get a gap-free sequence and a key is appended once`() {
        val user = user()
        val appended = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        try {
            pool.invokeAll(
                (1..120).map { n ->
                    Callable {
                        if (events.append(
                                user,
                                FRIEND,
                                "key-${n % 100}",
                                mapOf("n" to n),
                                NOW.plusSeconds(3600),
                            )
                        ) {
                            appended.incrementAndGet()
                        }
                    }
                },
            ).forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(100, appended.get())
        assertEquals(
            (1L..100L).toList(),
            jdbc.queryForList("SELECT seq FROM agent_events WHERE user_id = ? ORDER BY seq", Long::class.java, user),
        )
        assertEquals(100L, jdbc.queryForObject("SELECT last_event_seq FROM agent_users WHERE user_id = ?", Long::class.java, user))
    }

    @Test
    fun `a page holds the token's types, at most 100, with the cursor past skipped events`() {
        val user = user()
        events.append(user, SPORT, "sport-free:1:1", mapOf("sportLessons" to emptyList<Any>(), "absent" to null), NOW.plusSeconds(3600))
        repeat(101) { events.append(user, FRIEND, "friend-$it", mapOf("n" to it), NOW.plusSeconds(3600)) }
        events.append(user, SPORT, "sport-free:1:2", mapOf("sportLessons" to emptyList<Any>()), NOW.plusSeconds(3600))

        val sport = events.slice(user, 0, AgentScope.SPORT.eventTypes)
        assertEquals(listOf(1L, 103L), sport.events.map { it.seq })
        assertEquals("""{"sportLessons":[]}""", sport.events.first().payload.replace(" ", ""))
        assertEquals(103L, sport.nextCursor)
        assertFalse(sport.hasMore)
        assertFalse(sport.gap)

        val friends = events.slice(user, 0, AgentScope.FRIENDS_READ.eventTypes)
        assertEquals(100, friends.events.size)
        assertTrue(friends.hasMore)
        assertEquals(101L, friends.nextCursor)
        val rest = events.slice(user, friends.nextCursor, AgentScope.FRIENDS_READ.eventTypes)
        assertEquals(listOf(102L), rest.events.map { it.seq })
        assertEquals(103L, rest.nextCursor)

        assertEquals(0, events.slice(user, 0, emptySet()).events.size)
        assertEquals(103L, events.slice(user, 0, emptySet()).nextCursor)
    }

    @Test
    fun `retention drops expired and surplus events, and a cursor before them sees a gap`() {
        val user = user()
        events.append(user, FRIEND, "short", mapOf("n" to 1), NOW.plusSeconds(60))
        events.append(user, FRIEND, "long", mapOf("n" to 2), NOW.plusSeconds(3600))
        clock.advance(Duration.ofMinutes(2))
        retention.purge()
        assertEquals(listOf(2L), jdbc.queryForList("SELECT seq FROM agent_events WHERE user_id = ?", Long::class.java, user))
        assertTrue(events.slice(user, 0, AgentScope.FRIENDS_READ.eventTypes).gap)
        assertFalse(events.slice(user, 1, AgentScope.FRIENDS_READ.eventTypes).gap)

        jdbc.update(
            """
            INSERT INTO agent_events (user_id, seq, dedup_key, type, payload, created_at, expires_at)
            SELECT ?, s, 'bulk-' || s, ?, '{}'::jsonb, now(), now() + interval '1 day' FROM generate_series(3, 1005) s
            """,
            user,
            FRIEND,
        )
        jdbc.update("UPDATE agent_users SET last_event_seq = 1005 WHERE user_id = ?", user)
        retention.purge()
        assertEquals(1000, jdbc.queryForObject("SELECT count(*) FROM agent_events WHERE user_id = ?", Int::class.java, user))
        assertEquals(6L, jdbc.queryForObject("SELECT min(seq) FROM agent_events WHERE user_id = ?", Long::class.java, user))
        assertTrue(events.slice(user, 4, AgentScope.FRIENDS_READ.eventTypes).gap)
        assertFalse(events.slice(user, 5, AgentScope.FRIENDS_READ.eventTypes).gap)

        val ahead = events.slice(user, 2000, AgentScope.FRIENDS_READ.eventTypes)
        assertTrue(ahead.gap)
        assertEquals(1005L, ahead.nextCursor)
        // A new event continues the sequence; nothing is reused after retention.
        events.append(user, FRIEND, "next", mapOf("n" to 3), NOW.plusSeconds(3600))
        assertEquals(1006L, events.slice(user, 1005, AgentScope.FRIENDS_READ.eventTypes).events.single().seq)
    }

    @Test
    fun `an agent is live for sport only with a working ITMO session, recently polled and with access`() {
        val user = user()
        val token = tokens.issue(user, "nas", setOf(AgentScope.SPORT, AgentScope.FRIENDS_READ), AgentTokenOrigin.PAIR).entity.id
        assertFalse(reachability.isLive(user, AgentScope.SPORT))

        tokens.recordPoll(token, 0, itmoOk = false)
        assertFalse(reachability.isLive(user, AgentScope.SPORT))
        assertTrue(reachability.isLive(user, AgentScope.FRIENDS_READ))

        // A changed ITMO state is written at once, inside the one-minute heartbeat step.
        clock.advance(Duration.ofSeconds(10))
        tokens.recordPoll(token, 5, itmoOk = true)
        assertTrue(reachability.isLive(user, AgentScope.SPORT))
        assertEquals(5L, jdbc.queryForObject("SELECT acked_seq FROM agent_tokens WHERE id = ?", Long::class.java, token))

        // Within the step an unchanged poll writes nothing, so liveness counts from the last written heartbeat.
        clock.advance(Duration.ofSeconds(40))
        tokens.recordPoll(token, 6, itmoOk = true)
        assertEquals(5L, jdbc.queryForObject("SELECT acked_seq FROM agent_tokens WHERE id = ?", Long::class.java, token))
        clock.advance(Duration.ofMinutes(3))
        assertFalse(reachability.isLive(user, AgentScope.SPORT))
        tokens.recordPoll(token, 6, itmoOk = true)
        assertTrue(reachability.isLive(user, AgentScope.SPORT))
        assertFalse(reachability.isLive(user, AgentScope.SCHEDULE_WRITE))

        access.setGranted(user, false)
        assertFalse(reachability.isLive(user, AgentScope.SPORT))
        access.setGranted(user, true)
        tokens.revoke(user, token)
        assertFalse(reachability.isLive(user, AgentScope.SPORT))
    }

    private fun user(granted: Boolean = true): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, isu) VALUES (?, ?)", id, nextIsu.getAndIncrement())
        users.add(id)
        if (granted) access.setGranted(id, true)
        return id
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-12T09:00:00Z")
        const val SPORT = "SPORT_FREE_SIGN_LESSONS_PAYLOAD"
        const val FRIEND = "FRIENDSHIP_EVENT_PAYLOAD"
        val nextIsu = AtomicInteger(975001)
    }
}
