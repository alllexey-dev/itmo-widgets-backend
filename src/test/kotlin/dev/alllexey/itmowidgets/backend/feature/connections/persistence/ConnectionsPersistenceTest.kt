package dev.alllexey.itmowidgets.backend.feature.connections.persistence

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientInstanceEntity
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientKind
import dev.alllexey.itmowidgets.backend.feature.clients.service.ClientActivityService
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.model.ItmoStatus
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairKind
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientAccessService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientEventService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientEventSignals
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientPollService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientReachability
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionAuthService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionRetention
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionsConfig
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingClaim
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ScopeTexts
import dev.alllexey.itmowidgets.backend.feature.connections.web.ApprovePairingRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.ConnectionInfo
import dev.alllexey.itmowidgets.backend.feature.connections.web.CreateChallengeRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.CreatedChallenge
import dev.alllexey.itmowidgets.backend.feature.connections.web.ItmoSession
import dev.alllexey.itmowidgets.backend.feature.push.model.Device
import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.QueueTestClock
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.platform.security.ConnectionAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.ConnectionDecision
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
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

/** Pairing, connections, their tokens, the `client_session` rule, events and liveness on real PostgreSQL, committed. */
@Import(
    PairingService::class,
    ConnectionService::class,
    ConnectionAuthService::class,
    ClientAccessService::class,
    ClientEventStore::class,
    ClientEventService::class,
    ClientEventSignals::class,
    ClientPollService::class,
    ClientReachability::class,
    ConnectionRetention::class,
    ScopeTexts::class,
    ConnectionsPersistenceTest.Config::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConnectionsPersistenceTest : PostgreSqlRepositoryTest() {
    @TestConfiguration
    class Config {
        @Bean fun connectionsTestClock() = QueueTestClock()

        @Bean fun connectionsConfig() = ConnectionsConfig()

        @Bean fun connectionsTestJsonMapper(): JsonMapper = JsonMapper.builder().build()
    }

    @MockitoBean private lateinit var clients: ClientActivityService

    @MockitoBean private lateinit var devices: DeviceService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var clock: QueueTestClock

    @Autowired private lateinit var pairings: PairingService

    @Autowired private lateinit var connections: ConnectionService

    @Autowired private lateinit var auth: ConnectionAuthService

    @Autowired private lateinit var access: ClientAccessService

    @Autowired private lateinit var events: ClientEventService

    @Autowired private lateinit var polls: ClientPollService

    @Autowired private lateinit var reachability: ClientReachability

    @Autowired private lateinit var retention: ConnectionRetention

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
        jdbc.update("DELETE FROM pair_challenges WHERE client_ip = ?", IP)
        users.forEach { jdbc.update("DELETE FROM users WHERE id = ?", it) }
    }

    @Test
    fun `an agent is opened, approved and claimed once with its bundle and a token, and Backend keeps no secret`() {
        val phone = user()
        val created = pairings.create(request(ClientType.AGENT, scopes = listOf(ConnectionScope.SPORT, ConnectionScope.FRIENDS_READ)), IP)
        assertEquals(NOW.plus(PairingService.CODE_TTL), created.expiresAt)
        assertEquals(PairingClaim.Waiting(opened = false), pairings.claim(created.challengeId, created.claimSecret))

        val preview = pairings.open(phone, created.code.lowercase())
        assertEquals(PairKind.CONNECT, preview.kind)
        assertEquals(ClientType.AGENT, preview.client)
        assertEquals("nas", preview.name)
        assertEquals("linux", preview.platform)
        assertEquals(COMMITMENT_TEXT, preview.keyCommitment)
        assertEquals(listOf(ConnectionScope.SPORT, ConnectionScope.FRIENDS_READ), preview.requestedScopes.map { it.id })
        assertEquals("Запись на физкультуру", preview.requestedScopes.first().title)
        assertNull(preview.connectionId)
        assertEquals(NOW.plus(PairingService.OPENED_TTL), preview.expiresAt)
        clock.advance(Duration.ofSeconds(2))
        assertEquals(PairingClaim.Waiting(opened = true), pairings.claim(created.challengeId, created.claimSecret))

        val approval = ApprovePairingRequest(BUNDLE_TEXT, listOf(ConnectionScope.SPORT), ItmoSession(SID, NOW.plus(Duration.ofDays(90))))
        val connectionId = pairings.approve(phone, created.challengeId, approval)
        assertEquals(connectionId, pairings.approve(phone, created.challengeId, approval))
        val row = jdbc.queryForMap("SELECT * FROM pair_challenges WHERE id = ?", created.challengeId)
        assertFalse(row.values.any { it?.toString()?.contains(created.claimSecret) == true })

        clock.advance(Duration.ofSeconds(2))
        val claim = assertIs<PairingClaim.Claimed>(pairings.claim(created.challengeId, created.claimSecret))
        assertContentEquals(BUNDLE, claim.sealedBundle)
        val token = assertNotNull(claim.token)
        assertTrue(token.startsWith("iwc_"))
        assertEquals(connectionId, claim.connection.id)
        assertEquals(listOf(ConnectionScope.SPORT), granted(claim.connection))
        assertEquals(ClientType.AGENT.allowed.sorted(), claim.connection.scopes.map { it.scope.id })
        assertEquals(ItmoSession(SID, NOW.plus(Duration.ofDays(90))), claim.connection.itmo)
        val caller = assertNotNull(auth.resolve(token))
        assertEquals(phone, caller.userId)
        assertEquals(setOf("sport"), caller.scopes)
        assertEquals("agent", caller.client)
        assertFalse(jdbc.queryForMap("SELECT * FROM connections WHERE id = ?", connectionId).values.any { it == token })
        assertNull(
            jdbc.queryForObject("SELECT sealed_bundle FROM pair_challenges WHERE id = ?", ByteArray::class.java, created.challengeId),
        )
        clock.advance(Duration.ofSeconds(2))
        assertFailsWith<NotFoundException> { pairings.claim(created.challengeId, created.claimSecret) }
        clock.advance(Duration.ofSeconds(2))
        assertFailsWith<NotFoundException> { pairings.claim(created.challengeId, "c".repeat(43)) }

        val logged = logs.list.joinToString("\n") { it.formattedMessage }
        assertTrue(logged.contains(created.challengeId.toString()))
        for (secret in listOf(created.code, created.claimSecret, COMMITMENT_TEXT, BUNDLE_TEXT, token, SID, "nas")) {
            assertFalse(logged.contains(secret), "Logs must not carry $secret")
        }
    }

    @Test
    fun `the web connects without an ITMO session or bundle, an agent or MCP only with both`() {
        val phone = user()
        val web = open(phone, request(ClientType.WEB, name = "Chrome, macOS"))
        assertEquals(listOf(ConnectionScope.USER), pairings.open(phone, web.code).requestedScopes.map { it.id })
        assertFailsWith<InvalidRequestDataException> {
            pairings.approve(phone, web.challengeId, ApprovePairingRequest(BUNDLE_TEXT, null, null))
        }
        assertFailsWith<InvalidRequestDataException> {
            pairings.approve(phone, web.challengeId, ApprovePairingRequest(null, null, ItmoSession(SID, NOW.plusSeconds(60))))
        }
        pairings.approve(phone, web.challengeId, ApprovePairingRequest(null, null, null))
        val claim = assertIs<PairingClaim.Claimed>(pairings.claim(web.challengeId, web.claimSecret))
        assertNull(claim.sealedBundle)
        assertNull(claim.connection.itmo)
        assertEquals(ClientType.WEB, claim.connection.client)
        assertTrue(assertNotNull(auth.resolve(assertNotNull(claim.token))).freshForAdmin)

        val mcp = open(phone, request(ClientType.MCP))
        assertEquals(
            listOf(ConnectionScope.REVIEWS_READ, ConnectionScope.LINKS_READ),
            pairings.open(phone, mcp.code).requestedScopes.map { it.id },
        )
        assertFailsWith<InvalidRequestDataException> { pairings.approve(phone, mcp.challengeId, ApprovePairingRequest(null, null, null)) }
        assertFailsWith<InvalidRequestDataException> {
            pairings.approve(phone, mcp.challengeId, ApprovePairingRequest(BUNDLE_TEXT, null, null))
        }
        for (bad in listOf("", "not a sid", "x".repeat(65))) {
            assertFailsWith<InvalidRequestDataException> {
                pairings.approve(phone, mcp.challengeId, ApprovePairingRequest(BUNDLE_TEXT, null, ItmoSession(bad, NOW.plusSeconds(60))))
            }
        }
        for (expires in listOf(NOW, NOW.plus(Duration.ofDays(92)))) {
            assertFailsWith<InvalidRequestDataException> {
                pairings.approve(phone, mcp.challengeId, ApprovePairingRequest(BUNDLE_TEXT, null, ItmoSession(SID, expires)))
            }
        }
        assertFailsWith<InvalidRequestDataException> {
            pairings.approve(phone, mcp.challengeId, ApprovePairingRequest(BUNDLE_TEXT, listOf(ConnectionScope.SPORT_QUEUE), itmoSession()))
        }
        assertFailsWith<InvalidRequestDataException> {
            pairings.approve(phone, mcp.challengeId, ApprovePairingRequest(BUNDLE_TEXT, emptyList(), itmoSession()))
        }
        pairings.approve(phone, mcp.challengeId, ApprovePairingRequest(BUNDLE_TEXT, listOf(ConnectionScope.LINKS_READ), itmoSession()))
        val mcpClaim = assertIs<PairingClaim.Claimed>(pairings.claim(mcp.challengeId, mcp.claimSecret))
        assertEquals(listOf(ConnectionScope.LINKS_READ), granted(mcpClaim.connection))
    }

    @Test
    fun `challenges are checked against the client's scopes, access, the cap and the opener`() {
        val phone = user(granted = false)
        val stranger = user()
        assertFailsWith<InvalidRequestDataException> {
            pairings.create(request(ClientType.MCP, scopes = listOf(ConnectionScope.SPORT)), IP)
        }
        assertFailsWith<InvalidRequestDataException> {
            pairings.create(request(ClientType.WEB, scopes = listOf(ConnectionScope.LINKS_READ)), IP)
        }
        assertFailsWith<InvalidRequestDataException> { pairings.create(request(ClientType.AGENT).copy(kind = PairKind.RENEW), IP) }
        assertFailsWith<InvalidRequestDataException> {
            pairings.create(request(ClientType.AGENT).copy(keyCommitment = PairingService.encode(ByteArray(31))), IP)
        }
        assertFailsWith<InvalidRequestDataException> { pairings.create(request(ClientType.AGENT, name = " "), IP) }
        assertFailsWith<InvalidRequestDataException> { pairings.create(request(ClientType.AGENT, name = "a\nb"), IP) }

        val agent = pairings.create(request(ClientType.AGENT), IP)
        assertFailsWith<PermissionDeniedException> { pairings.open(phone, agent.code) }
        access.setAgentGranted(phone, true)
        pairings.open(phone, agent.code)
        assertFailsWith<NotFoundException> { pairings.open(stranger, agent.code) }
        assertFailsWith<NotFoundException> {
            pairings.approve(stranger, agent.challengeId, ApprovePairingRequest(BUNDLE_TEXT, null, itmoSession()))
        }
        assertEquals(agent.challengeId, pairings.open(phone, agent.code).challengeId)
        assertFailsWith<NotFoundException> { pairings.open(phone, "NOTACODE") }
        assertFailsWith<InvalidRequestDataException> {
            pairings.approve(phone, agent.challengeId, ApprovePairingRequest(PairingService.encode(ByteArray(47)), null, itmoSession()))
        }
        assertFailsWith<InvalidRequestDataException> {
            pairings.approve(phone, agent.challengeId, ApprovePairingRequest("not base64!", null, itmoSession()))
        }

        repeat(5) { connect(phone, ClientType.AGENT) }
        assertFailsWith<BusinessRuleException> {
            pairings.approve(phone, agent.challengeId, ApprovePairingRequest(BUNDLE_TEXT, null, itmoSession()))
        }
        // The cap is per client type.
        connect(phone, ClientType.MCP)
    }

    @Test
    fun `codes live 2 minutes unopened, 10 opened, and an approval is claimable for 1 more minute`() {
        val phone = user()
        val unopened = pairings.create(request(ClientType.WEB), IP)
        val opened = open(phone, request(ClientType.WEB))

        clock.advance(Duration.ofMinutes(3))
        assertFailsWith<NotFoundException> { pairings.claim(unopened.challengeId, unopened.claimSecret) }
        assertFailsWith<NotFoundException> { pairings.open(phone, unopened.code) }
        assertEquals(PairingClaim.Waiting(opened = true), pairings.claim(opened.challengeId, opened.claimSecret))

        pairings.approve(phone, opened.challengeId, ApprovePairingRequest(null, null, null))
        clock.advance(Duration.ofMinutes(7).plusSeconds(30))
        // Past expiresAt, inside the grace: still claimable.
        pairings.expireChallenges()
        assertIs<PairingClaim.Claimed>(pairings.claim(opened.challengeId, opened.claimSecret))

        val late = open(phone, request(ClientType.AGENT))
        pairings.approve(phone, late.challengeId, ApprovePairingRequest(BUNDLE_TEXT, null, itmoSession()))
        clock.advance(Duration.ofMinutes(11).plusSeconds(1))
        pairings.expireChallenges()
        assertNull(jdbc.queryForObject("SELECT sealed_bundle FROM pair_challenges WHERE id = ?", ByteArray::class.java, late.challengeId))
        assertFailsWith<NotFoundException> { pairings.claim(late.challengeId, late.claimSecret) }

        // The unclaimed approval is revoked, keeping its session id until the phone ends it.
        clock.advance(Duration.ofMinutes(5))
        retention.purge()
        val abandoned = connections.devices(phone, null).connections.single { it.client == ClientType.AGENT }
        assertNotNull(abandoned.revokedAt)
        assertEquals(SID, abandoned.itmo?.sid)
    }

    @Test
    fun `a renewal goes only to the live connection's owner, replaces its session and approval and carries no token`() {
        val phone = user()
        val stranger = user()
        val (token, id) = connect(phone, ClientType.AGENT)
        clock.advance(Duration.ofDays(8))
        assertFalse(assertNotNull(auth.resolve(token)).freshForAdmin)

        val renewal = pairings.renew(id, COMMITMENT_TEXT, IP)
        assertFailsWith<NotFoundException> { pairings.open(stranger, renewal.code) }
        val preview = pairings.open(phone, renewal.code)
        assertEquals(PairKind.RENEW, preview.kind)
        assertEquals(id, preview.connectionId)
        assertFailsWith<InvalidRequestDataException> {
            pairings.approve(phone, renewal.challengeId, ApprovePairingRequest(BUNDLE_TEXT, listOf(ConnectionScope.SPORT), itmoSession()))
        }
        val renewed = ItmoSession(SID_2, clock.instant().plus(Duration.ofDays(90)))
        assertEquals(id, pairings.approve(phone, renewal.challengeId, ApprovePairingRequest(BUNDLE_TEXT, null, renewed)))
        val claim = assertIs<PairingClaim.Claimed>(pairings.claim(renewal.challengeId, renewal.claimSecret))
        assertNull(claim.token)
        assertContentEquals(BUNDLE, claim.sealedBundle)
        assertEquals(renewed, claim.connection.itmo)
        assertEquals(clock.instant(), claim.connection.approvedAt)
        assertTrue(assertNotNull(auth.resolve(token)).freshForAdmin)
        assertFalse(auth.isClientSession(SID))
        assertTrue(auth.isClientSession(SID_2))

        val second = pairings.renew(id, COMMITMENT_TEXT, IP)
        pairings.open(phone, second.code)
        pairings.approve(phone, second.challengeId, ApprovePairingRequest(BUNDLE_TEXT, null, renewed))
        connections.revoke(phone, id)
        assertFailsWith<NotFoundException> { pairings.claim(second.challengeId, second.claimSecret) }
        assertNull(jdbc.queryForObject("SELECT sealed_bundle FROM pair_challenges WHERE id = ?", ByteArray::class.java, second.challengeId))
        assertFailsWith<NotFoundException> { pairings.renew(id, COMMITMENT_TEXT, IP) }
    }

    @Test
    fun `concurrent claims take an approval exactly once`() {
        val phone = user()
        val created = open(phone, request(ClientType.AGENT))
        pairings.approve(phone, created.challengeId, ApprovePairingRequest(BUNDLE_TEXT, null, itmoSession()))
        val claimed = AtomicInteger()
        val ready = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(6)
        try {
            pool.invokeAll(
                (1..6).map {
                    Callable {
                        ready.await(5, TimeUnit.SECONDS)
                        val claim = runCatching { pairings.claim(created.challengeId, created.claimSecret) }.getOrNull()
                        if (claim is PairingClaim.Claimed) claimed.incrementAndGet()
                    }
                }.also { ready.countDown() },
            ).forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, claimed.get())
        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM connections WHERE user_id = ? AND token_hash IS NOT NULL", Int::class.java, phone),
        )
    }

    @Test
    fun `a client's ITMO session stays a client session after revoke until the phone ends it or it expires`() {
        val phone = user()
        val other = user()
        val (token, id) = connect(phone, ClientType.MCP)
        assertTrue(auth.isClientSession(SID))
        assertFailsWith<BusinessRuleException> { connections.itmoEnded(phone, id) }

        assertFailsWith<NotFoundException> { connections.revoke(other, id) }
        connections.revoke(phone, id)
        assertNull(auth.resolve(token))
        assertFailsWith<NotFoundException> { connections.revoke(phone, id) }
        assertTrue(auth.isClientSession(SID))
        val listed = connections.devices(phone, null).connections.single()
        assertNotNull(listed.revokedAt)
        assertEquals(SID, listed.itmo?.sid)

        assertFailsWith<NotFoundException> { connections.itmoEnded(other, id) }
        connections.itmoEnded(phone, id)
        connections.itmoEnded(phone, id)
        assertFalse(auth.isClientSession(SID))
        assertEquals(emptyList(), connections.devices(phone, null).connections)

        val (_, second) = connect(phone, ClientType.MCP)
        connections.revoke(phone, second)
        clock.advance(Duration.ofDays(91))
        assertFalse(auth.isClientSession(SID))
        retention.purge()
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM connections WHERE itmo_sid IS NOT NULL AND user_id = ?", Int::class.java, phone),
        )
    }

    @Test
    fun `scopes narrow from anywhere, widen only from the app and never past the client's allowed set`() {
        val phone = user()
        val (_, id) = connect(phone, ClientType.AGENT, listOf(ConnectionScope.SPORT, ConnectionScope.FRIENDS_READ))
        assertEquals(
            listOf(ConnectionScope.SPORT),
            granted(connections.updateScopes(phone, id, setOf(ConnectionScope.SPORT), mayWiden = false)),
        )
        assertFailsWith<PermissionDeniedException> {
            connections.updateScopes(phone, id, setOf(ConnectionScope.SPORT, ConnectionScope.SCHEDULE_WRITE), mayWiden = false)
        }
        val widened = connections.updateScopes(phone, id, setOf(ConnectionScope.SPORT, ConnectionScope.SCHEDULE_WRITE), mayWiden = true)
        assertEquals(listOf(ConnectionScope.SPORT, ConnectionScope.SCHEDULE_WRITE), granted(widened))
        assertFailsWith<InvalidRequestDataException> {
            connections.updateScopes(phone, id, setOf(ConnectionScope.SPORT_QUEUE), mayWiden = true)
        }
        assertFailsWith<InvalidRequestDataException> { connections.updateScopes(phone, id, emptySet(), mayWiden = true) }
        assertFailsWith<NotFoundException> { connections.updateScopes(user(), id, setOf(ConnectionScope.SPORT), mayWiden = true) }
    }

    @Test
    fun `a web connection ends after 30 idle days and reaches admin routes only within 7 days of its approval`() {
        val phone = user()
        val (token, id) = connect(phone, ClientType.WEB)
        clock.advance(Duration.ofDays(6))
        assertTrue(assertNotNull(auth.resolve(token)).freshForAdmin)
        clock.advance(Duration.ofDays(2))
        assertFalse(assertNotNull(auth.resolve(token)).freshForAdmin)
        clock.advance(Duration.ofDays(30))
        assertNull(auth.resolve(token))
        assertEquals(true, jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM connections WHERE id = ?", Boolean::class.java, id))
    }

    @Test
    fun `access, the route policy and the rate limit decide per request`() {
        val phone = user()
        val (token, _) = connect(phone, ClientType.AGENT)
        val agent = assertNotNull(auth.resolve(token))
        assertEquals(ConnectionDecision.ALLOWED, auth.decide(agent, "GET", "/api/client/me"))
        assertEquals(ConnectionDecision.DENIED, auth.decide(agent, "GET", "/api/users/me/devices"))
        access.setAgentGranted(phone, false)
        assertEquals(ConnectionDecision.DISABLED, auth.decide(agent, "GET", "/api/client/me"))
        assertEquals(ConnectionDecision.ALLOWED, auth.decide(agent, "DELETE", "/api/client/self"))
        access.setAgentGranted(phone, true)
        repeat(ConnectionAuthService.ROUTES_PER_MINUTE - 2) { auth.decide(agent, "GET", "/api/client/me") }
        assertEquals(ConnectionDecision.RATE_LIMITED, auth.decide(agent, "GET", "/api/client/me"))
        assertEquals(ConnectionDecision.ALLOWED, auth.decide(agent, "GET", "/api/client/events"))
    }

    @Test
    fun `devices join app installs with their push registration and mark the caller's`() {
        val phone = user()
        val install = UUID.randomUUID()
        val other = UUID.randomUUID()
        fun instance(id: UUID, kind: ClientKind, sid: String?) = ClientInstanceEntity(
            UUID.randomUUID(), phone, kind, id, "2.4.0", null, null, null, null, null, "36", null, false, NOW, NOW, sid,
        )
        `when`(
            clients.installs(phone),
        ).thenReturn(listOf(instance(install, ClientKind.ANDROID, SID), instance(other, ClientKind.IOS, null)))
        val device = Device(
            user = User(id = phone, isu = 1, pictureUrl = null, name = null, createdAt = NOW),
            fcmToken = "t",
            deviceName = "Google Pixel 8",
            lastLogin = NOW,
        ).apply { installId = install }
        `when`(devices.devicesByInstall(phone)).thenReturn(mapOf(install to device))

        val installs = connections.devices(phone, install).installs
        assertEquals(listOf(install, other), installs.map { it.instanceId })
        assertEquals(listOf(AppPlatform.ANDROID, AppPlatform.IOS), installs.map { it.platform })
        assertEquals(listOf("Google Pixel 8", null), installs.map { it.deviceName })
        assertEquals(listOf(true, false), installs.map { it.pushEnabled })
        assertEquals(listOf(SID, null), installs.map { it.sid })
        assertEquals(listOf(true, false), installs.map { it.current })

        assertFailsWith<InvalidRequestDataException> { connections.signOutInstall(phone, install, install) }
        `when`(devices.unregisterInstall(phone, other)).thenReturn(0)
        connections.signOutInstall(phone, other, install)
        assertFailsWith<NotFoundException> { connections.signOutInstall(phone, UUID.randomUUID(), install) }
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
            jdbc.queryForList("SELECT seq FROM client_events WHERE user_id = ? ORDER BY seq", Long::class.java, user),
        )
        assertEquals(100L, jdbc.queryForObject("SELECT last_event_seq FROM client_users WHERE user_id = ?", Long::class.java, user))
    }

    @Test
    fun `a page holds the scope's types, at most 100, and retention leaves a gap before what it dropped`() {
        val user = user()
        events.append(user, SPORT, "sport-free:1:1", mapOf("sportLessons" to emptyList<Any>(), "absent" to null), NOW.plusSeconds(3600))
        repeat(101) { events.append(user, FRIEND, "friend-$it", mapOf("n" to it), NOW.plusSeconds(3600)) }
        events.append(user, SPORT, "sport-free:1:2", mapOf("sportLessons" to emptyList<Any>()), NOW.plusSeconds(3600))

        val sport = events.slice(user, 0, ConnectionScope.SPORT.eventTypes)
        assertEquals(listOf(1L, 103L), sport.events.map { it.seq })
        assertEquals("""{"sportLessons":[]}""", sport.events.first().payload.replace(" ", ""))
        val friends = events.slice(user, 0, ConnectionScope.FRIENDS_READ.eventTypes)
        assertEquals(100, friends.events.size)
        assertTrue(friends.hasMore)
        assertEquals(listOf(102L), events.slice(user, friends.nextCursor, ConnectionScope.FRIENDS_READ.eventTypes).events.map { it.seq })

        jdbc.update("UPDATE client_events SET expires_at = ? WHERE user_id = ? AND seq <= 2", java.sql.Timestamp.from(NOW), user)
        retention.purge()
        assertTrue(events.slice(user, 0, ConnectionScope.FRIENDS_READ.eventTypes).gap)
        assertFalse(events.slice(user, 2, ConnectionScope.FRIENDS_READ.eventTypes).gap)
    }

    @Test
    fun `an agent is live for sport only with a working ITMO session, recently polled and with access`() {
        val user = user()
        val (token, _) = connect(user, ClientType.AGENT, listOf(ConnectionScope.SPORT, ConnectionScope.FRIENDS_READ))
        val agent = assertNotNull(auth.resolve(token))
        assertFalse(reachability.isLive(user, ConnectionScope.SPORT))

        poll(agent, "closed")
        assertFalse(reachability.isLive(user, ConnectionScope.SPORT))
        assertTrue(reachability.isLive(user, ConnectionScope.FRIENDS_READ))

        // A changed ITMO state is written at once, inside the one-minute heartbeat step.
        clock.advance(Duration.ofSeconds(10))
        poll(agent, "ok")
        assertTrue(reachability.isLive(user, ConnectionScope.SPORT))
        assertEquals(ItmoStatus.OK, connections.get(agent.connectionId).itmoStatus)
        clock.advance(Duration.ofMinutes(4))
        assertFalse(reachability.isLive(user, ConnectionScope.SPORT))
        poll(agent, "ok")
        assertTrue(reachability.isLive(user, ConnectionScope.SPORT))
        assertFalse(reachability.isLive(user, ConnectionScope.SCHEDULE_WRITE))

        access.setAgentGranted(user, false)
        assertFalse(reachability.isLive(user, ConnectionScope.SPORT))
        access.setAgentGranted(user, true)
        connections.reportStatus(agent.connectionId, ItmoStatus.CLOSED, "1.3.0")
        assertFalse(reachability.isLive(user, ConnectionScope.SPORT))
        assertEquals("1.3.0", connections.get(agent.connectionId).version)

        // An MCP server is never live, whatever it reports.
        val (mcpToken, _) = connect(user, ClientType.MCP, listOf(ConnectionScope.SPORT_QUEUE))
        connections.reportStatus(assertNotNull(auth.resolve(mcpToken)).connectionId, ItmoStatus.OK, null)
        assertFalse(reachability.isLive(user, ConnectionScope.SPORT))
    }

    private fun granted(connection: ConnectionInfo) = connection.scopes.filter { it.granted }.map { it.scope.id }

    private fun poll(agent: ConnectionAuthentication, itmo: String) = polls.poll(agent, 0, 0, itmo, {}, { throw it })

    private fun request(client: ClientType, name: String = "nas", scopes: List<ConnectionScope>? = null) =
        CreateChallengeRequest(PairKind.CONNECT, client, COMMITMENT_TEXT, name, "1.2.0", "linux", scopes)

    private fun open(phone: UUID, request: CreateChallengeRequest): CreatedChallenge =
        pairings.create(request, IP).also { pairings.open(phone, it.code) }

    private fun itmoSession() = ItmoSession(SID, clock.instant().plus(Duration.ofDays(90)))

    /** A claimed connection: its token and id. */
    private fun connect(phone: UUID, client: ClientType, scopes: List<ConnectionScope>? = null): Pair<String, UUID> {
        val created = open(phone, request(client, scopes = scopes))
        val approval = if (client.hasItmoSession) {
            ApprovePairingRequest(
                BUNDLE_TEXT,
                scopes,
                itmoSession(),
            )
        } else {
            ApprovePairingRequest(null, null, null)
        }
        pairings.approve(phone, created.challengeId, approval)
        val claim = assertIs<PairingClaim.Claimed>(pairings.claim(created.challengeId, created.claimSecret))
        return assertNotNull(claim.token) to claim.connection.id
    }

    private fun user(granted: Boolean = true): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, isu, enrolled_at) VALUES (?, ?, now())", id, nextIsu.getAndIncrement())
        users.add(id)
        if (granted) access.setAgentGranted(id, true)
        return id
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-12T09:00:00Z")
        const val IP = "198.51.100.78"
        const val SID = "8d1f0c2e-1b6a-4c55-9a43-0f2d7e6b1a01"
        const val SID_2 = "8d1f0c2e-1b6a-4c55-9a43-0f2d7e6b1a02"
        const val SPORT = "SPORT_FREE_SIGN_LESSONS_PAYLOAD"
        const val FRIEND = "FRIENDSHIP_EVENT_PAYLOAD"
        val COMMITMENT = ByteArray(32) { it.toByte() }
        val COMMITMENT_TEXT = PairingService.encode(COMMITMENT)
        val BUNDLE = ByteArray(80) { (it * 3).toByte() }
        val BUNDLE_TEXT = PairingService.encode(BUNDLE)
        val nextIsu = AtomicInteger(977001)
    }
}
