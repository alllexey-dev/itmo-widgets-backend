package dev.alllexey.itmowidgets.backend.feature.connections.persistence

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.alllexey.itmowidgets.backend.feature.clients.service.ClientActivityService
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientGrant
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.model.Grants
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairKind
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientAccessService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientAssertions
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientEventService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientEventSignals
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientPollService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientReachability
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionAuthService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionClientRegistry
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionRetention
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionRoutePolicy
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionsConfig
import dev.alllexey.itmowidgets.backend.feature.connections.service.EdDsaJws
import dev.alllexey.itmowidgets.backend.feature.connections.service.IdentityTokens
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingClaim
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ScopeTexts
import dev.alllexey.itmowidgets.backend.feature.connections.web.ApprovePairingRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.ClientAppInfo
import dev.alllexey.itmowidgets.backend.feature.connections.web.CreateChallengeRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.GrantRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.ItmoSession
import dev.alllexey.itmowidgets.backend.feature.connections.web.PairingGrant
import dev.alllexey.itmowidgets.backend.feature.connections.web.RenewChallengeRequest
import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.QueueTestClock
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.InvalidClientException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounters
import dev.alllexey.itmowidgets.backend.testing.TestClients
import dev.alllexey.itmowidgets.backend.testing.TestKeys
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.security.KeyPair
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Step 2 of the client platform on real PostgreSQL, committed, with an identity signing key: a confidential client
 * proving itself at create, renewal and claim, the `identity` grant and its JWT, connections without Widgets scopes.
 */
@Import(
    PairingService::class,
    ConnectionService::class,
    ConnectionAuthService::class,
    ClientAccessService::class,
    ClientEventStore::class,
    ClientEventService::class,
    ClientEventSignals::class,
    ConnectionClientRegistry::class,
    ConnectionClientRepository::class,
    ClientAssertions::class,
    IdentityTokens::class,
    ClientPollService::class,
    ClientReachability::class,
    ConnectionRetention::class,
    ScopeTexts::class,
    DailyCounters::class,
    ConfidentialClientsPersistenceTest.Config::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConfidentialClientsPersistenceTest : PostgreSqlRepositoryTest() {
    /**
     * The properties say what the bean holds: Spring binds the context's properties onto a `@ConfigurationProperties`
     * bean, and a value that differs from an immutable one fails the context.
     */
    @TestConfiguration
    class Config {
        @Bean fun confidentialTestClock() = QueueTestClock()

        @Bean
        fun confidentialConnectionsConfig() = ConnectionsConfig(identity = ConnectionsConfig.Identity(IDENTITY_KEY_TEXT, KEY_ID))

        @Bean fun confidentialTestJsonMapper(): JsonMapper = JsonMapper.builder().build()
    }

    @MockitoBean private lateinit var clients: ClientActivityService

    @MockitoBean private lateinit var devices: DeviceService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var clock: QueueTestClock

    @Autowired private lateinit var pairings: PairingService

    @Autowired private lateinit var connections: ConnectionService

    @Autowired private lateinit var auth: ConnectionAuthService

    @Autowired private lateinit var registry: ConnectionClientRegistry

    @Autowired private lateinit var texts: ScopeTexts

    private val json = JsonMapper.builder().build()
    private val users = mutableListOf<UUID>()
    private val logger = LoggerFactory.getLogger(ClientAssertions::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun start() {
        // As a request with `Accept-Language: ru` sets it.
        LocaleContextHolder.setLocale(ScopeTexts.FALLBACK)
        clock.set(NOW)
        registry.save(SERVICE)
        logs.start()
        logger.addAppender(logs)
    }

    @AfterEach
    fun cleanup() {
        LocaleContextHolder.resetLocaleContext()
        logger.detachAppender(logs)
        jdbc.update("DELETE FROM connection_pair_challenges WHERE client_ip = ?", IP)
        users.forEach { jdbc.update("DELETE FROM users WHERE id = ?", it) }
        jdbc.update("DELETE FROM connection_clients WHERE id LIKE 'synthetic-%'")
        registry.reload()
    }

    @Test
    fun `a confidential client proves itself at create, renew and claim, and gets identity with no scope`() {
        val phone = user("Тестов Тест")
        val isu = isuOf(phone)
        assertFailsWith<InvalidClientException> { pairings.create(createRequest(null), IP) }
        assertFailsWith<InvalidClientException> { pairings.create(createRequest(assertion(key = TestKeys.random())), IP) }
        val first = assertion()
        val created = pairings.create(createRequest(first), IP)
        assertFailsWith<InvalidClientException> { pairings.create(createRequest(first), IP) }

        val preview = pairings.open(phone, created.code, 2)
        assertEquals(2, preview.protocol)
        assertEquals(ClientType.APP, preview.client)
        assertEquals(
            ClientAppInfo(SERVICE.id, SERVICE.name, SERVICE.homepage, null, SERVICE.deployment, true, "https://example.com/connected"),
            preview.app,
        )
        val (title, description) = texts.identity(ScopeTexts.FALLBACK)
        assertEquals(
            listOf(
                PairingGrant(Grants.IDENTITY, true, title, description, SERVICE.grant(Grants.IDENTITY)?.purpose, null),
                PairingGrant(DEV_REFRESH, false, null, null, SERVICE.grant(DEV_REFRESH)?.purpose, null),
            ),
            preview.grants,
        )
        assertEquals(emptyList(), preview.requestedScopes)
        assertFailsWith<InvalidRequestDataException> {
            pairings.approve(phone, created.challengeId, ApprovePairingRequest(null, null, null, emptyList()))
        }
        val id = pairings.approve(phone, created.challengeId, ApprovePairingRequest(null, null, null, listOf(Grants.IDENTITY)))

        // The claim checks the assertion against the challenge's client before the claim secret.
        assertFailsWith<InvalidClientException> { pairings.claim(created.challengeId, created.claimSecret) }
        clock.advance(Duration.ofSeconds(2))
        assertFailsWith<InvalidClientException> { pairings.claim(created.challengeId, "wrong", null) }
        clock.advance(Duration.ofSeconds(2))
        assertFailsWith<NotFoundException> { pairings.claim(created.challengeId, "a".repeat(43), assertion()) }
        clock.advance(Duration.ofSeconds(2))
        val claim = assertIs<PairingClaim.Claimed>(pairings.claim(created.challengeId, created.claimSecret, assertion()))
        assertEquals(id, claim.connection.id)
        assertNull(claim.sealedBundle)
        assertTrue(claim.connection.identity)
        assertTrue(claim.connection.app.verified)
        assertEquals(listOf(ConnectionScope.LINKS_READ to false), claim.connection.scopes.map { it.scope.id to it.granted })
        verifyIdentity(assertNotNull(claim.identity), isu, "Тестов Тест", id, issuedAt = clock.instant())

        // A connection with identity alone holds a token that reaches the client routes and no scoped route.
        val client = assertNotNull(auth.resolve(assertNotNull(claim.token)))
        assertEquals(emptySet(), client.scopes)
        assertNotNull(ConnectionRoutePolicy.permitted(ClientType.APP, emptySet(), "GET", "/api/client/me"))
        assertNull(ConnectionRoutePolicy.permitted(ClientType.APP, emptySet(), "GET", "/api/subjects/42/links"))
        for (scopes in listOf(setOf(ConnectionScope.LINKS_READ), emptySet())) {
            assertFailsWith<InvalidRequestDataException> { connections.updateScopes(phone, id, scopes, mayWiden = true) }
        }

        // A renewal proves itself too; identity asked again comes again, marked as held.
        assertFailsWith<InvalidClientException> {
            pairings.renew(id, RenewChallengeRequest(COMMITMENT, listOf(GrantRequest(Grants.IDENTITY))), IP)
        }
        assertFailsWith<InvalidRequestDataException> {
            pairings.renew(id, RenewChallengeRequest(COMMITMENT, listOf(GrantRequest("widgets:links:read")), assertion()), IP)
        }
        val renewal = pairings.renew(id, RenewChallengeRequest(COMMITMENT, listOf(GrantRequest(Grants.IDENTITY)), assertion()), IP)
        val renewPreview = pairings.open(phone, renewal.code, 2)
        assertEquals(PairKind.RENEW, renewPreview.kind)
        assertEquals(listOf(Triple(Grants.IDENTITY, true, true)), renewPreview.grants?.map { Triple(it.id, it.required, it.granted) })
        pairings.approve(phone, renewal.challengeId, ApprovePairingRequest(null, null, null, listOf(Grants.IDENTITY)))
        clock.advance(Duration.ofMinutes(1))
        val renewed = assertIs<PairingClaim.Claimed>(pairings.claim(renewal.challengeId, renewal.claimSecret, assertion()))
        assertNull(renewed.token)
        verifyIdentity(assertNotNull(renewed.identity), isu, "Тестов Тест", id, issuedAt = clock.instant())

        // A renewal for an ITMO grant alone hands no identity over.
        val itmo = pairings.renew(id, RenewChallengeRequest(COMMITMENT, listOf(GrantRequest(DEV_REFRESH)), assertion()), IP)
        assertEquals(listOf(DEV_REFRESH to false), pairings.open(phone, itmo.code, 2).grants?.map { it.id to it.granted })
        val session = ItmoSession(SID, clock.instant().plus(Duration.ofDays(90)))
        pairings.approve(phone, itmo.challengeId, ApprovePairingRequest(BUNDLE, null, session, listOf(DEV_REFRESH)))
        clock.advance(Duration.ofSeconds(2))
        val sealed = assertIs<PairingClaim.Claimed>(pairings.claim(itmo.challengeId, itmo.claimSecret, assertion()))
        assertNull(sealed.identity)
        assertNotNull(sealed.sealedBundle)
        assertEquals(listOf(DEV_REFRESH), sealed.connection.itmoGrants)
        assertTrue(sealed.connection.identity)

        val logged = logs.list.joinToString("\n") { it.formattedMessage }
        assertFalse(logged.contains(first.substringAfterLast('.')), "Logs must not carry an assertion")
        assertFalse(logged.contains(assertNotNull(claim.identity).substringAfterLast('.')), "Logs must not carry an identity token")
        assertTrue(logged.contains("Client assertion of ${SERVICE.id} rejected: replayed jti"))
    }

    @Test
    fun `identity goes only to a connection granted it, and a user without a name gets a token without one`() {
        registry.save(
            SERVICE.copy(
                grants = listOf(
                    ClientGrant("widgets:links:read", required = false, requestedByDefault = true, purpose = null),
                    ClientGrant(Grants.IDENTITY, required = false, requestedByDefault = true, purpose = null),
                ),
            ),
        )
        val phone = user(null)
        val declined = pairings.create(createRequest(assertion(), grants = null), IP)
        assertEquals(listOf("widgets:links:read", Grants.IDENTITY), pairings.open(phone, declined.code, 2).grants?.map { it.id })
        pairings.approve(phone, declined.challengeId, ApprovePairingRequest(null, null, null, listOf("widgets:links:read")))
        val bare = assertIs<PairingClaim.Claimed>(pairings.claim(declined.challengeId, declined.claimSecret, assertion()))
        assertNull(bare.identity)
        assertFalse(bare.connection.identity)
        assertEquals(listOf(ConnectionScope.LINKS_READ to true), bare.connection.scopes.map { it.scope.id to it.granted })
        // A renewal may add identity later; it is never taken away.
        val added = pairings.renew(
            bare.connection.id,
            RenewChallengeRequest(COMMITMENT, listOf(GrantRequest(Grants.IDENTITY)), assertion()),
            IP,
        )
        assertEquals(listOf(Grants.IDENTITY to false), pairings.open(phone, added.code, 2).grants?.map { it.id to it.granted })
        pairings.approve(phone, added.challengeId, ApprovePairingRequest(null, null, null, listOf(Grants.IDENTITY)))
        val later = assertIs<PairingClaim.Claimed>(pairings.claim(added.challengeId, added.claimSecret, assertion()))
        verifyIdentity(assertNotNull(later.identity), isuOf(phone), null, bare.connection.id, issuedAt = clock.instant())
        clock.advance(Duration.ofMinutes(1))
        val kept = pairings.renew(
            bare.connection.id,
            RenewChallengeRequest(COMMITMENT, listOf(GrantRequest(Grants.IDENTITY)), assertion()),
            IP,
        )
        pairings.open(phone, kept.code, 2)
        pairings.approve(phone, kept.challengeId, ApprovePairingRequest(null, null, null, emptyList()))
        assertTrue(connections.get(bare.connection.id).identity)

        // A public client never sends an assertion.
        val mcp = CreateChallengeRequest(PairKind.CONNECT, ClientType.MCP, COMMITMENT, "nas", clientAssertion = assertion())
        assertFailsWith<InvalidRequestDataException> { pairings.create(mcp, IP) }
        val legacy = pairings.create(mcp.copy(clientAssertion = null), IP)
        assertFailsWith<InvalidRequestDataException> { pairings.claim(legacy.challengeId, legacy.claimSecret, assertion()) }
    }

    @Test
    fun `used assertion ids go with the pairing expiry once they expired`() {
        pairings.create(createRequest(assertion(jti = "kept-for-a-while")), IP)
        assertEquals(1, assertionCount())
        clock.advance(Duration.ofSeconds(80))
        pairings.expireChallenges()
        assertEquals(1, assertionCount())
        clock.advance(Duration.ofSeconds(20))
        pairings.expireChallenges()
        assertEquals(0, assertionCount())
    }

    @Test
    fun `the JWKS carries the public half of the configured key`() {
        val jwk = pairings.jwks().keys.single()
        assertEquals(TestKeys.publicKeyText(IDENTITY_KEY), jwk.x)
        assertEquals(listOf("OKP", "Ed25519", "test-1", "sig", "EdDSA"), listOf(jwk.kty, jwk.crv, jwk.kid, jwk.use, jwk.alg))
    }

    /** [token] verifies against the JWKS and carries exactly these claims. */
    private fun verifyIdentity(token: String, isu: Int, name: String?, connectionId: UUID, issuedAt: Instant) {
        val parsed = assertNotNull(EdDsaJws.parse(token, 4096))
        val key = assertNotNull(EdDsaJws.decode(pairings.jwks().keys.single().x)?.let(EdDsaJws::publicKey))
        assertTrue(parsed.verifiedBy(key))
        assertEquals("""{"alg":"EdDSA","kid":"test-1","typ":"JWT"}""", json.writeValueAsString(parsed.header))
        val jti = parsed.claims.get("jti").stringValue()
        assertEquals(jti, UUID.fromString(jti).toString())
        val iat = issuedAt.epochSecond
        assertEquals(
            """{"iss":"https://widgets.alllexey.dev","aud":"${SERVICE.id}","sub":"$isu","isu":$isu,""" +
                (name?.let { """"name":"$it",""" } ?: "") +
                """"cid":"$connectionId","jti":"$jti","iat":$iat,"exp":${iat + 300}}""",
            json.writeValueAsString(parsed.claims),
        )
    }

    private fun assertion(jti: String = UUID.randomUUID().toString(), key: KeyPair = TestClients.SERVICE_KEY): String {
        val now = clock.instant().epochSecond
        return EdDsaJws.sign(
            linkedMapOf("alg" to "EdDSA", "kid" to "2026-11", "typ" to "JWT"),
            linkedMapOf(
                "iss" to SERVICE.id,
                "sub" to SERVICE.id,
                "aud" to "https://widgets.alllexey.dev",
                "jti" to jti,
                "iat" to now,
                "exp" to now + 60,
            ),
            key.private,
        )
    }

    private fun createRequest(
        assertion: String?,
        grants: List<GrantRequest>? = listOf(GrantRequest(Grants.IDENTITY), GrantRequest(DEV_REFRESH)),
    ) = CreateChallengeRequest(PairKind.CONNECT, null, COMMITMENT, "Synthetic service", null, null, null, SERVICE.id, grants, assertion)

    private fun assertionCount(): Int = checkNotNull(
        jdbc.queryForObject("SELECT count(*) FROM connection_client_assertions WHERE client_id = ?", Int::class.java, SERVICE.id),
    )

    private fun isuOf(user: UUID): Int = checkNotNull(jdbc.queryForObject("SELECT isu FROM users WHERE id = ?", Int::class.java, user))

    private fun user(name: String?): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, isu, name, enrolled_at) VALUES (?, ?, ?, now())", id, nextIsu.getAndIncrement(), name)
        users.add(id)
        return id
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-28T09:00:00Z")
        const val IP = "198.51.100.80"
        const val SID = "8d1f0c2e-1b6a-4c55-9a43-0f2d7e6b1b03"
        const val DEV_REFRESH = "itmo:refresh:student-personal-cabinet-dev"
        val SERVICE = TestClients.SERVICE
        val IDENTITY_KEY: KeyPair = TestKeys.random()
        val IDENTITY_KEY_TEXT = TestKeys.privateKeyText(IDENTITY_KEY.private)
        const val KEY_ID = "test-1"

        @JvmStatic
        @DynamicPropertySource
        fun identityProperties(registry: DynamicPropertyRegistry) {
            registry.add("itmowidgets.connections.identity.private-key") { IDENTITY_KEY_TEXT }
            registry.add("itmowidgets.connections.identity.key-id") { KEY_ID }
        }
        val COMMITMENT: String = PairingService.encode(ByteArray(32) { it.toByte() })
        val BUNDLE: String = PairingService.encode(ByteArray(80) { (it * 3).toByte() })
        val nextIsu = AtomicInteger(977001)
    }
}
