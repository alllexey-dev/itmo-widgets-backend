package dev.alllexey.itmowidgets.backend.feature.weblogin.persistence

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentScope
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentTokenOrigin
import dev.alllexey.itmowidgets.backend.feature.agent.persistence.AgentEventStore
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentAccessService
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentConfig
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentReachability
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentTokenService
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.QueueTestClock
import dev.alllexey.itmowidgets.backend.feature.weblogin.model.PairingKind
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.ConnectionsService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.PairingClaim
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.PairingService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebLoginService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionConfig
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.feature.weblogin.web.ClaimResult
import dev.alllexey.itmowidgets.backend.feature.weblogin.web.ConnectionKind
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pairing, the old web login next to it and the connections list on real PostgreSQL, committed, with a controllable clock. */
@Import(
    PairingService::class, WebLoginService::class, WebSessionService::class, ConnectionsService::class, AgentTokenService::class,
    AgentAccessService::class, AgentEventStore::class, AgentReachability::class, PairingPersistenceTest.Config::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PairingPersistenceTest : PostgreSqlRepositoryTest() {
    @TestConfiguration
    @EnableConfigurationProperties(WebSessionConfig::class, AgentConfig::class)
    class Config {
        @Bean fun pairingTestClock() = QueueTestClock()
    }

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var clock: QueueTestClock

    @Autowired private lateinit var pairings: PairingService

    @Autowired private lateinit var logins: WebLoginService

    @Autowired private lateinit var sessions: WebSessionService

    @Autowired private lateinit var connections: ConnectionsService

    @Autowired private lateinit var tokens: AgentTokenService

    @Autowired private lateinit var access: AgentAccessService

    private val users = mutableListOf<UUID>()
    private val logger = LoggerFactory.getLogger(PairingService::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun start() {
        clock.set(NOW)
        logs.start()
        logger.addAppender(logs)
    }

    @AfterEach
    fun cleanup() {
        logger.detachAppender(logs)
        jdbc.update("DELETE FROM web_login_challenges WHERE client_ip = ?", IP)
        users.forEach { jdbc.update("DELETE FROM users WHERE id = ?", it) }
    }

    @Test
    fun `a new agent is opened, approved and claimed once with its bundle and a token, and Backend keeps no secret`() {
        val phone = user()
        val created = pairings.create(PairingKind.AGENT, COMMITMENT_TEXT, " nas ", "1.0.0", "agent/1.0", IP)
        assertEquals(NOW.plus(WebLoginService.CODE_TTL), created.expiresAt)
        assertIs<PairingClaim.Pending>(pairings.claim(created.id, created.pollSecret))

        val preview = pairings.open(phone, created.code.lowercase())
        assertEquals(PairingKind.AGENT, preview.kind)
        assertEquals("nas", preview.name)
        assertEquals("1.0.0", preview.version)
        assertContentEquals(COMMITMENT, preview.keyCommitment)
        assertNull(preview.scopes)
        assertEquals(NOW.plus(PairingService.OPENED_TTL), preview.expiresAt)
        assertIs<PairingClaim.Opened>(pairings.claim(created.id, created.pollSecret))

        pairings.approve(phone, created.id, BUNDLE_TEXT, setOf(AgentScope.SPORT, AgentScope.FRIENDS_READ))
        pairings.approve(phone, created.id, BUNDLE_TEXT, setOf(AgentScope.SPORT))
        val row = jdbc.queryForMap("SELECT * FROM web_login_challenges WHERE id = ?", created.id)
        assertFalse(row.values.any { it?.toString()?.contains(created.pollSecret) == true })
        assertEquals("sport,friends:read", row["scopes"])

        val claim = assertIs<PairingClaim.Approved>(pairings.claim(created.id, created.pollSecret))
        assertContentEquals(BUNDLE, claim.sealedBundle)
        val token = assertNotNull(claim.agentToken)
        assertEquals(token.entity.id, claim.connectionId)
        assertEquals(AgentTokenOrigin.PAIR, token.entity.createdVia)
        assertEquals(setOf("sport", "friends:read"), tokens.resolve(token.token)?.scopes)
        assertNull(jdbc.queryForObject("SELECT sealed_bundle FROM web_login_challenges WHERE id = ?", ByteArray::class.java, created.id))
        assertIs<PairingClaim.Expired>(pairings.claim(created.id, created.pollSecret))
        assertFailsWith<NotFoundException> { pairings.claim(created.id, "wrong-secret") }

        val logged = logs.list.joinToString("\n") { it.formattedMessage }
        assertTrue(logged.contains(created.id.toString()))
        for (secret in listOf(created.code, created.pollSecret, COMMITMENT_TEXT, BUNDLE_TEXT, token.token)) {
            assertFalse(logged.contains(secret), "Logs must not carry $secret")
        }
    }

    @Test
    fun `the first account that opens a code owns it`() {
        val phone = user()
        val stranger = user()
        val created = pairings.create(PairingKind.AGENT, COMMITMENT_TEXT, "nas", null, null, IP)
        pairings.open(phone, created.code)
        assertFailsWith<NotFoundException> { pairings.open(stranger, created.code) }
        assertFailsWith<NotFoundException> { pairings.approve(stranger, created.id, BUNDLE_TEXT, setOf(AgentScope.SPORT)) }
        assertFailsWith<NotFoundException> { pairings.reject(stranger, created.id) }
        assertEquals(created.id, pairings.open(phone, created.code).challengeId)
        assertFailsWith<NotFoundException> { pairings.open(phone, "NOTACODE") }
    }

    @Test
    fun `approvals are checked per kind and against access and the token cap`() {
        val phone = user(granted = false)
        val created = pairings.create(PairingKind.AGENT, COMMITMENT_TEXT, "nas", null, null, IP)
        assertFailsWith<PermissionDeniedException> { pairings.open(phone, created.code) }
        access.setGranted(phone, true)
        pairings.open(phone, created.code)
        assertFailsWith<InvalidRequestDataException> { pairings.approve(phone, created.id, null, setOf(AgentScope.SPORT)) }
        assertFailsWith<InvalidRequestDataException> { pairings.approve(phone, created.id, BUNDLE_TEXT, null) }
        assertFailsWith<InvalidRequestDataException> { pairings.approve(phone, created.id, BUNDLE_TEXT, emptySet()) }
        assertFailsWith<InvalidRequestDataException> {
            pairings.approve(phone, created.id, PairingService.encode(ByteArray(47)), setOf(AgentScope.SPORT))
        }
        assertFailsWith<InvalidRequestDataException> {
            pairings.approve(phone, created.id, PairingService.encode(ByteArray(16_385)), setOf(AgentScope.SPORT))
        }
        assertFailsWith<InvalidRequestDataException> { pairings.approve(phone, created.id, "not base64!", setOf(AgentScope.SPORT)) }
        repeat(5) { tokens.issue(phone, "agent $it", setOf(AgentScope.SPORT), AgentTokenOrigin.APP) }
        assertFailsWith<BusinessRuleException> { pairings.approve(phone, created.id, BUNDLE_TEXT, setOf(AgentScope.SPORT)) }

        assertFailsWith<InvalidRequestDataException> {
            pairings.create(PairingKind.AGENT, PairingService.encode(ByteArray(31)), "nas", null, null, IP)
        }
        assertFailsWith<InvalidRequestDataException> { pairings.create(PairingKind.WEB_SESSION, COMMITMENT_TEXT, "nas", null, null, IP) }
        assertFailsWith<InvalidRequestDataException> { pairings.create(PairingKind.AGENT, COMMITMENT_TEXT, " ", null, null, IP) }
        assertFailsWith<InvalidRequestDataException> { pairings.create(PairingKind.AGENT, COMMITMENT_TEXT, "a\nb", null, null, IP) }
    }

    @Test
    fun `codes live 2 minutes unopened, 10 opened, and an approval is claimable for 1 more minute`() {
        val phone = user()
        val unopened = pairings.create(PairingKind.AGENT, COMMITMENT_TEXT, "nas", null, null, IP)
        val opened = pairings.create(PairingKind.AGENT, COMMITMENT_TEXT, "nas", null, null, IP)
        pairings.open(phone, opened.code)

        clock.advance(Duration.ofMinutes(3))
        assertIs<PairingClaim.Expired>(pairings.claim(unopened.id, unopened.pollSecret))
        assertFailsWith<NotFoundException> { pairings.open(phone, unopened.code) }
        assertIs<PairingClaim.Opened>(pairings.claim(opened.id, opened.pollSecret))

        pairings.approve(phone, opened.id, BUNDLE_TEXT, setOf(AgentScope.SPORT))
        clock.advance(Duration.ofMinutes(7).plusSeconds(30))
        // Past expiresAt, inside the grace: the bundle is still there and claimable.
        logins.expireChallenges()
        assertIs<PairingClaim.Approved>(pairings.claim(opened.id, opened.pollSecret))

        val late = pairings.create(PairingKind.AGENT, COMMITMENT_TEXT, "nas", null, null, IP)
        pairings.open(phone, late.code)
        pairings.approve(phone, late.id, BUNDLE_TEXT, setOf(AgentScope.SPORT))
        clock.advance(Duration.ofMinutes(11).plusSeconds(1))
        logins.expireChallenges()
        assertNull(jdbc.queryForObject("SELECT sealed_bundle FROM web_login_challenges WHERE id = ?", ByteArray::class.java, late.id))
        assertIs<PairingClaim.Expired>(pairings.claim(late.id, late.pollSecret))
    }

    @Test
    fun `a rejected pairing tells the target and cannot be approved`() {
        val phone = user()
        val created = pairings.create(PairingKind.AGENT, COMMITMENT_TEXT, "nas", null, null, IP)
        pairings.open(phone, created.code)
        pairings.reject(phone, created.id)
        pairings.reject(phone, created.id)
        assertIs<PairingClaim.Rejected>(pairings.claim(created.id, created.pollSecret))
        assertFailsWith<NotFoundException> { pairings.approve(phone, created.id, BUNDLE_TEXT, setOf(AgentScope.SPORT)) }
    }

    @Test
    fun `a session hand-off goes only to the token's owner, carries no token and ends with a revoked token`() {
        val phone = user()
        val stranger = user()
        val agent = tokens.issue(phone, "nas", setOf(AgentScope.SPORT), AgentTokenOrigin.APP).entity
        val created = pairings.createSession(agent.id, COMMITMENT_TEXT, IP)
        assertFailsWith<NotFoundException> { pairings.open(stranger, created.code) }
        val preview = pairings.open(phone, created.code)
        assertEquals(PairingKind.AGENT_SESSION, preview.kind)
        assertEquals("nas", preview.name)
        assertEquals(setOf(AgentScope.SPORT), preview.scopes)
        assertFailsWith<InvalidRequestDataException> { pairings.approve(phone, created.id, BUNDLE_TEXT, setOf(AgentScope.SPORT)) }
        pairings.approve(phone, created.id, BUNDLE_TEXT, null)
        val claim = assertIs<PairingClaim.Approved>(pairings.claim(created.id, created.pollSecret))
        assertNull(claim.agentToken)
        assertEquals(agent.id, claim.connectionId)

        val second = pairings.createSession(agent.id, COMMITMENT_TEXT, IP)
        pairings.open(phone, second.code)
        pairings.approve(phone, second.id, BUNDLE_TEXT, null)
        connections.revoke(phone, agent.id)
        assertIs<PairingClaim.Expired>(pairings.claim(second.id, second.pollSecret))
        assertNull(jdbc.queryForObject("SELECT sealed_bundle FROM web_login_challenges WHERE id = ?", ByteArray::class.java, second.id))
        assertFailsWith<NotFoundException> { pairings.createSession(agent.id, COMMITMENT_TEXT, IP) }
    }

    @Test
    fun `concurrent polls claim an approval exactly once`() {
        val phone = user()
        val created = pairings.create(PairingKind.AGENT, COMMITMENT_TEXT, "nas", null, null, IP)
        pairings.open(phone, created.code)
        pairings.approve(phone, created.id, BUNDLE_TEXT, setOf(AgentScope.SPORT))
        val approved = AtomicInteger()
        val ready = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(6)
        try {
            pool.invokeAll(
                (1..6).map {
                    Callable {
                        ready.await(5, TimeUnit.SECONDS)
                        if (pairings.claim(created.id, created.pollSecret) is PairingClaim.Approved) approved.incrementAndGet()
                    }
                }.also { ready.countDown() },
            ).forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, approved.get())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM agent_tokens WHERE user_id = ?", Int::class.java, phone))
    }

    @Test
    fun `the old web login never sees a keyed pairing, and a web sign-in may go through pairing`() {
        val phone = user()
        val agent = pairings.create(PairingKind.AGENT, COMMITMENT_TEXT, "nas", null, null, IP)
        assertFailsWith<NotFoundException> { logins.preview(phone, agent.code) }
        assertFailsWith<NotFoundException> { logins.approve(phone, agent.id) }
        assertFailsWith<NotFoundException> { logins.claim(agent.id, agent.pollSecret) }

        val web = logins.createChallenge("Synthetic browser", IP)
        assertFailsWith<NotFoundException> { pairings.claim(web.id, web.pollSecret) }
        val preview = pairings.open(phone, web.code)
        assertEquals(PairingKind.WEB_SESSION, preview.kind)
        assertNull(preview.keyCommitment)
        assertFailsWith<InvalidRequestDataException> { pairings.approve(phone, web.id, BUNDLE_TEXT, null) }
        pairings.approve(phone, web.id, null, null)
        val claim = assertIs<ClaimResult.Approved>(logins.claim(web.id, web.pollSecret))
        assertEquals(phone, claim.userId)
        assertNotNull(claim.sessionId)
    }

    @Test
    fun `connections list web sessions and agents and revoke only the caller's`() {
        val phone = user()
        val other = user()
        val web = sessions.issueSession(phone, "Synthetic browser")
        val agent = tokens.issue(phone, "nas", setOf(AgentScope.SPORT, AgentScope.FRIENDS_READ), AgentTokenOrigin.APP).entity
        clock.advance(Duration.ofSeconds(30))
        tokens.recordPoll(agent.id, 0, itmoOk = true)

        val listed = connections.list(phone, web.id)
        assertEquals(listOf(agent.id, web.id), listed.map { it.id })
        val agentRow = listed.first()
        assertEquals(ConnectionKind.AGENT, agentRow.kind)
        assertTrue(agentRow.live)
        assertTrue(agentRow.itmoSession)
        assertEquals(listOf(AgentScope.SPORT, AgentScope.FRIENDS_READ), agentRow.scopes)
        val webRow = listed.last()
        assertTrue(webRow.current)
        assertEquals("Synthetic browser", webRow.userAgent)
        assertFalse(webRow.live)

        assertFailsWith<NotFoundException> { connections.revoke(other, agent.id) }
        assertFailsWith<NotFoundException> { connections.revoke(other, web.id) }
        connections.revoke(phone, agent.id)
        connections.revoke(phone, web.id)
        assertFailsWith<NotFoundException> { connections.revoke(phone, web.id) }
        assertEquals(emptyList(), connections.list(phone, null))
        assertNull(sessions.resolve(web.token))
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
        const val IP = "198.51.100.77"
        val COMMITMENT = ByteArray(32) { it.toByte() }
        val COMMITMENT_TEXT = PairingService.encode(COMMITMENT)
        val BUNDLE = ByteArray(80) { (it * 3).toByte() }
        val BUNDLE_TEXT = PairingService.encode(BUNDLE)
        val nextIsu = AtomicInteger(976001)
    }
}
