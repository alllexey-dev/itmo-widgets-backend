package dev.alllexey.itmowidgets.backend.feature.connections.persistence

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.alllexey.itmowidgets.backend.feature.clients.service.ClientActivityService
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientAuth
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientDeployment
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientGrant
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientKey
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientStatus
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionClient
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.model.Grants
import dev.alllexey.itmowidgets.backend.feature.connections.model.ItmoStatus
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairKind
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairStatus
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientAccessService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientAssertions
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientEventService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientEventSignals
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientPollService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientReachability
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionAuthService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionClientRegistry
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionRetention
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionService
import dev.alllexey.itmowidgets.backend.feature.connections.service.EdDsaJws
import dev.alllexey.itmowidgets.backend.feature.connections.service.IdentityTokens
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingClaim
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ScopeTexts
import dev.alllexey.itmowidgets.backend.feature.connections.web.ApprovePairingRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.CreateChallengeRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.CreatedChallenge
import dev.alllexey.itmowidgets.backend.feature.connections.web.GrantRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.ItmoSession
import dev.alllexey.itmowidgets.backend.feature.connections.web.PairingGrant
import dev.alllexey.itmowidgets.backend.feature.connections.web.RenewChallengeRequest
import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.QueueTestClock
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.IdentityUnavailableException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidClientException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.platform.error.UpdateRequiredException
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounters
import dev.alllexey.itmowidgets.backend.testing.TestClients
import dev.alllexey.itmowidgets.backend.testing.TestKeys
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client registry, the protocol-2 pairing with grants and taking a client's ITMO access back, on real PostgreSQL,
 * committed. Shares the Spring context of [ConnectionsPersistenceTest], which pins the protocol-1 behaviour.
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
    ConnectionsPersistenceTest.Config::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ClientPlatformPersistenceTest : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var clients: ClientActivityService

    @MockitoBean private lateinit var devices: DeviceService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var clock: QueueTestClock

    @Autowired private lateinit var pairings: PairingService

    @Autowired private lateinit var connections: ConnectionService

    @Autowired private lateinit var auth: ConnectionAuthService

    @Autowired private lateinit var access: ClientAccessService

    @Autowired private lateinit var registry: ConnectionClientRegistry

    @Autowired private lateinit var texts: ScopeTexts

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

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
        jdbc.update("DELETE FROM connection_pair_challenges WHERE client_ip = ?", IP)
        users.forEach { jdbc.update("DELETE FROM users WHERE id = ?", it) }
        jdbc.update("DELETE FROM connection_clients WHERE id LIKE 'synthetic-%'")
        registry.reload()
    }

    @Test
    fun `the seeded registry is the three released client types`() {
        registry.reload()
        assertEquals(
            TestClients.SEEDED.sortedBy { it.id },
            registry.all().map { it.copy(createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH) }.sortedBy { it.id },
        )
        assertEquals(listOf(ConnectionScope.SPORT, ConnectionScope.FRIENDS_READ, ConnectionScope.SCHEDULE_WRITE), TestClients.AGENT.scopes)
        assertEquals(listOf(ConnectionScope.USER), TestClients.WEB.scopes)
        TestClients.SEEDED.forEach {
            assertEquals(it, registry.get(checkNotNull(it.kind.legacyClientId)).copy(createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
        }
    }

    @Test
    fun `a registry write is validated, replaces the grants as a whole and is read after the commit`() {
        val app = synthetic("synthetic-tool")
        val confidential = synthetic("synthetic-keyed").copy(auth = ClientAuth.CONFIDENTIAL, deployment = ClientDeployment.HOSTED)
        val first = registry.save(app)
        assertTrue(first.created)
        assertEquals(app.grants, registry.get(app.id).grants)

        val changed = app.copy(name = "Synthetic tool 2", grants = app.grants.reversed().drop(1), maxPerUser = 3)
        assertFalse(registry.save(changed).created)
        assertEquals(changed.grants, registry.get(app.id).grants)
        assertEquals("Synthetic tool 2", registry.get(app.id).name)
        assertEquals(first.client.createdAt, registry.get(app.id).createdAt)

        val rejected = listOf(
            app.copy(id = "Bad Id"),
            app.copy(id = "-leading"),
            app.copy(name = " "),
            app.copy(name = "x".repeat(61)),
            app.copy(name = " padded "),
            app.copy(homepage = "http://example.com"),
            app.copy(iconUrl = "https://"),
            app.copy(homepage = "https://example.com/" + "x".repeat(200)),
            app.copy(maxPerUser = 0),
            app.copy(maxPerUser = 101),
            app.copy(grants = app.grants + app.grants.first()),
            app.copy(grants = app.grants + ClientGrant("widgets:unknown", false, false, null)),
            app.copy(grants = app.grants + ClientGrant("itmo:refresh:Another_Client", false, false, null)),
            app.copy(grants = app.grants + ClientGrant("itmo:refresh:", false, false, null)),
            app.copy(grants = app.grants + ClientGrant("itmo:unknown", false, false, null)),
            app.copy(grants = app.grants + ClientGrant("identity:name", false, false, null)),
            app.copy(grants = app.grants.filter { Grants.isItmo(it.id) }),
            app.copy(returnUrl = "http://example.com/back"),
            app.copy(returnUrl = "https://example.com/" + "x".repeat(200)),
            // Keys only for a confidential client, each a distinct well-formed kid with a raw 32-byte key.
            app.copy(keys = listOf(ClientKey("k1", KEY))),
            confidential.copy(keys = listOf(ClientKey("k1", KEY), ClientKey("k1", OTHER_KEY))),
            confidential.copy(keys = listOf(ClientKey("bad kid", KEY))),
            confidential.copy(keys = listOf(ClientKey("k".repeat(65), KEY))),
            confidential.copy(keys = listOf(ClientKey("k1", KEY.dropLast(1)))),
            confidential.copy(keys = listOf(ClientKey("k1", "$KEY="))),
            confidential.copy(keys = listOf(ClientKey("k1", EdDsaJws.encode(ByteArray(33))))),
            app.copy(grants = app.grants.map { it.copy(purpose = "") }),
            app.copy(grants = app.grants.map { it.copy(purpose = "x".repeat(201)) }),
            // The SSO cookie only for a self-hosted client, never an ITMO grant for the web.
            app.copy(deployment = ClientDeployment.HOSTED, grants = app.grants + ClientGrant(Grants.ITMO_SSO, false, false, null)),
            TestClients.WEB.copy(grants = TestClients.WEB.grants + ClientGrant(Grants.ITMO_REFRESH, false, false, null)),
            // A registered client keeps its kind.
            changed.copy(kind = ClientType.MCP),
        )
        rejected.forEach { assertFailsWith<InvalidRequestDataException>(it.toString()) { registry.save(it) } }
        assertEquals(changed.grants, registry.get(app.id).grants)
        registry.save(app.copy(grants = app.grants + ClientGrant(Grants.ITMO_SSO, false, false, "Синтетика")))
        assertEquals(Grants.ITMO_SSO, registry.get(app.id).grants.last().id)

        // Any ITMO OIDC client and identity are grant ids; identity alone is enough for an entry, of any kind.
        val identityOnly = app.copy(
            grants = listOf(
                ClientGrant(Grants.IDENTITY, false, true, null),
                ClientGrant("itmo:refresh:student-personal-cabinet-dev", false, true, null),
            ),
            returnUrl = "https://example.com/back",
        )
        registry.save(identityOnly)
        assertEquals(identityOnly.copy(createdAt = first.client.createdAt), registry.get(app.id).copy(updatedAt = Instant.EPOCH))
        registry.save(TestClients.WEB.copy(id = "synthetic-web", grants = listOf(ClientGrant(Grants.IDENTITY, true, true, null))))

        // A key keeps its created_at while it stays; PUT replaces the set.
        registry.save(confidential.copy(keys = listOf(ClientKey("k1", KEY))))
        registry.save(confidential.copy(keys = listOf(ClientKey("k1", KEY), ClientKey("k2.next_-", OTHER_KEY))))
        assertEquals(listOf("k1", "k2.next_-"), registry.get(confidential.id).keys.map { it.kid })
        registry.save(confidential.copy(keys = listOf(ClientKey("k2.next_-", OTHER_KEY))))
        assertEquals(listOf(ClientKey("k2.next_-", OTHER_KEY)), registry.get(confidential.id).keys)
        registry.save(confidential)
        assertEquals(emptyList(), registry.get(confidential.id).keys)
    }

    @Test
    fun `suspending a client revokes its live connections and ends its challenges in the same transaction`() {
        val phone = user()
        val client = synthetic("synthetic-suspended")
        registry.save(client)
        val (_, live) = connect(phone, client.id, listOf(GrantRequest("widgets:links:read")), listOf("widgets:links:read"))
        val (_, other) = connect(phone, "itmo-mcp", listOf(GrantRequest("widgets:links:read")), listOf("widgets:links:read"))
        val pending = pairings.create(createRequest(client.id, listOf(GrantRequest("widgets:links:read"))), IP)

        // A rolled-back suspension changes nothing.
        assertFailsWith<IllegalStateException> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                registry.save(client.copy(status = ClientStatus.SUSPENDED))
                error("rolled back")
            }
        }
        assertNull(connections.get(live).revokedAt)

        val saved = registry.save(client.copy(status = ClientStatus.SUSPENDED))
        assertEquals(1, saved.revokedConnections)
        assertNotNull(connections.get(live).revokedAt)
        assertNull(connections.get(other).revokedAt)
        assertEquals(
            PairStatus.EXPIRED.name,
            jdbc.queryForObject("SELECT status FROM connection_pair_challenges WHERE id = ?", String::class.java, pending.challengeId),
        )
        assertFailsWith<PermissionDeniedException> {
            pairings.create(createRequest(client.id, listOf(GrantRequest("widgets:links:read"))), IP)
        }

        registry.save(client)
        val again = pairings.create(createRequest(client.id, listOf(GrantRequest("widgets:links:read"))), IP)
        registry.save(client.copy(status = ClientStatus.SUSPENDED))
        assertFailsWith<NotFoundException> { pairings.open(phone, again.code, 2) }
    }

    @Test
    fun `a protocol-1 request maps to its seeded client and opens for released and new apps alike`() {
        val phone = user()
        val created = pairings.create(legacyRequest(ClientType.MCP), IP)
        assertEquals(1, protocolOf(created))
        val released = pairings.open(phone, created.code)
        assertNull(released.protocol)
        assertNull(released.app)
        assertNull(released.grants)
        assertEquals(listOf(ConnectionScope.REVIEWS_READ, ConnectionScope.LINKS_READ), released.requestedScopes.map { it.id })

        val preview = pairings.open(phone, created.code, 2)
        assertEquals(1, preview.protocol)
        assertEquals("itmo-mcp", preview.app?.id)
        assertEquals(ClientDeployment.SELF_HOSTED, preview.app?.deployment)
        assertEquals(false, preview.app?.verified)
        val reviews = texts.info(ConnectionScope.REVIEWS_READ, ScopeTexts.FALLBACK)
        val links = texts.info(ConnectionScope.LINKS_READ, ScopeTexts.FALLBACK)
        assertEquals(
            listOf(
                PairingGrant("widgets:reviews:read", false, reviews.title, reviews.description, null, null),
                PairingGrant("widgets:links:read", false, links.title, links.description, null, null),
                PairingGrant(Grants.ITMO_REFRESH, true, null, null, TestClients.MCP.grant(Grants.ITMO_REFRESH)?.purpose, null),
            ),
            preview.grants,
        )
        assertFailsWith<InvalidRequestDataException> {
            pairings.approve(
                phone,
                created.challengeId,
                ApprovePairingRequest(BUNDLE_TEXT, null, itmoSession(), listOf(Grants.ITMO_REFRESH)),
            )
        }
        pairings.approve(phone, created.challengeId, ApprovePairingRequest(BUNDLE_TEXT, null, itmoSession()))
        val claim = assertIs<PairingClaim.Claimed>(pairings.claim(created.challengeId, created.claimSecret))
        assertEquals(listOf(Grants.ITMO_REFRESH), claim.connection.itmoGrants)
        assertEquals("itmo-mcp", claim.connection.clientId)
        assertEquals(ClientType.MCP, claim.connection.client)

        // client and clientId name the same seeded entry; one of them, exactly.
        assertEquals(1, protocolOf(pairings.create(legacyRequest(ClientType.MCP).copy(client = null, clientId = "itmo-mcp"), IP)))
        for (bad in listOf(
            legacyRequest(ClientType.MCP).copy(clientId = "itmo-mcp"),
            legacyRequest(ClientType.MCP).copy(client = null),
            legacyRequest(ClientType.APP),
            legacyRequest(ClientType.MCP).copy(client = null, clientId = "unknown"),
            legacyRequest(ClientType.MCP, listOf(ConnectionScope.SPORT)),
            legacyRequest(ClientType.MCP, listOf(ConnectionScope.LINKS_READ)).copy(grants = listOf(GrantRequest("widgets:links:read"))),
        )) {
            assertFailsWith<InvalidRequestDataException>(bad.toString()) { pairings.create(bad, IP) }
        }
    }

    @Test
    fun `grants are checked against the registry, required ones are added and never dropped`() {
        val agent = pairings.create(createRequest("itmo-widgets-agent", listOf(GrantRequest("widgets:friends:read", required = true))), IP)
        assertEquals(2, protocolOf(agent))
        assertEquals(
            """[{"id":"widgets:friends:read","required":true},{"id":"itmo:refresh:student-personal-cabinet","required":true}]""",
            requestedOf(agent),
        )
        val mcp = pairings.create(
            createRequest(
                "itmo-mcp",
                listOf(
                    GrantRequest(Grants.ITMO_SSO),
                    GrantRequest("widgets:reviews:read", required = false),
                    GrantRequest(Grants.ITMO_REFRESH),
                ),
            ),
            IP,
        )
        assertEquals(
            """[{"id":"widgets:reviews:read","required":false},{"id":"itmo:refresh:student-personal-cabinet","required":false},""" +
                """{"id":"itmo:sso","required":false}]""",
            requestedOf(mcp),
        )
        val web = pairings.create(createRequest("web", listOf(GrantRequest("widgets:user", required = false))), IP)
        assertEquals("""[{"id":"widgets:user","required":true}]""", requestedOf(web))

        for (grants in listOf(
            listOf(GrantRequest("widgets:sport")),
            listOf(GrantRequest("widgets:reviews:read"), GrantRequest("widgets:reviews:read")),
            listOf(GrantRequest("widgets:reviews:read"), GrantRequest("itmo:refresh:another-client")),
            listOf(GrantRequest(Grants.ITMO_REFRESH)),
            emptyList(),
        )) {
            assertFailsWith<InvalidRequestDataException>(grants.toString()) { pairings.create(createRequest("itmo-mcp", grants), IP) }
        }
        assertFailsWith<InvalidRequestDataException> {
            pairings.create(createRequest("web", listOf(GrantRequest(Grants.ITMO_REFRESH))), IP)
        }

        // A confidential client without keys is stored, but cannot pair.
        registry.save(synthetic("synthetic-confidential").copy(auth = ClientAuth.CONFIDENTIAL))
        assertFailsWith<InvalidClientException> {
            pairings.create(createRequest("synthetic-confidential", listOf(GrantRequest("widgets:links:read"))), IP)
        }
    }

    @Test
    fun `without a signing key identity is unavailable at create and renew, and the rest pairs as before`() {
        val phone = user()
        registry.save(
            synthetic("synthetic-identity").copy(
                grants = listOf(
                    ClientGrant("widgets:links:read", required = false, requestedByDefault = true, purpose = null),
                    ClientGrant(Grants.IDENTITY, required = false, requestedByDefault = false, purpose = null),
                ),
            ),
        )
        assertEquals(emptyList(), pairings.jwks().keys)
        assertFailsWith<IdentityUnavailableException> {
            pairings.create(createRequest("synthetic-identity", listOf(GrantRequest(Grants.IDENTITY))), IP)
        }
        val (_, id) = connect(phone, "synthetic-identity", listOf(GrantRequest("widgets:links:read")), listOf("widgets:links:read"))
        assertFailsWith<IdentityUnavailableException> {
            pairings.renew(id, RenewChallengeRequest(COMMITMENT_TEXT, listOf(GrantRequest(Grants.IDENTITY))), IP)
        }
        // A required identity is requested without being asked for, and is unavailable the same way.
        registry.save(
            synthetic("synthetic-identity").copy(
                grants = listOf(
                    ClientGrant("widgets:links:read", required = false, requestedByDefault = true, purpose = null),
                    ClientGrant(Grants.IDENTITY, required = true, requestedByDefault = true, purpose = null),
                ),
            ),
        )
        assertFailsWith<IdentityUnavailableException> {
            pairings.create(createRequest("synthetic-identity", listOf(GrantRequest("widgets:links:read"))), IP)
        }
        assertFailsWith<IdentityUnavailableException> {
            pairings.create(legacyRequest(ClientType.MCP).copy(client = null, clientId = "synthetic-identity"), IP)
        }
        assertFalse(connections.get(id).identity)
    }

    @Test
    fun `a protocol-2 challenge needs an app that opens with protocol 2 and is approved with granted`() {
        val phone = user()
        val created = pairings.create(
            createRequest(
                "itmo-mcp",
                listOf(GrantRequest("widgets:reviews:read"), GrantRequest(Grants.ITMO_REFRESH), GrantRequest(Grants.ITMO_SSO)),
            ),
            IP,
        )
        assertFailsWith<UpdateRequiredException> { pairings.open(phone, created.code) }
        assertFailsWith<UpdateRequiredException> { pairings.open(phone, created.code, 1) }
        assertEquals(PairingClaim.Waiting(opened = false), pairings.claim(created.challengeId, created.claimSecret))

        val preview = pairings.open(phone, created.code, 2)
        assertEquals(2, preview.protocol)
        assertEquals(listOf("widgets:reviews:read", Grants.ITMO_REFRESH, Grants.ITMO_SSO), preview.grants?.map { it.id })
        assertEquals(listOf(ConnectionScope.REVIEWS_READ), preview.requestedScopes.map { it.id })
        assertEquals(TestClients.MCP.grant(Grants.ITMO_SSO)?.purpose, preview.grants?.last()?.purpose)
        assertTrue(preview.grants!!.all { it.granted == null })

        fun approve(
            granted: List<String>?,
            bundle: String? = BUNDLE_TEXT,
            itmo: ItmoSession? = itmoSession(),
            scopes: List<ConnectionScope>? = null,
        ) = pairings.approve(phone, created.challengeId, ApprovePairingRequest(bundle, scopes, itmo, granted))
        for (attempt in listOf(
            { approve(null) },
            { approve(listOf("widgets:reviews:read", Grants.ITMO_SSO), scopes = listOf(ConnectionScope.REVIEWS_READ)) },
            { approve(listOf("widgets:reviews:read", "widgets:links:read")) },
            { approve(listOf(Grants.ITMO_SSO)) },
            { approve(listOf("widgets:reviews:read", Grants.ITMO_SSO), bundle = null) },
            { approve(listOf("widgets:reviews:read", Grants.ITMO_SSO), itmo = null) },
            { approve(listOf("widgets:reviews:read")) },
            { approve(listOf("widgets:reviews:read"), bundle = null) },
        )) {
            assertFailsWith<InvalidRequestDataException> { attempt() }
        }
        val id = approve(listOf("widgets:reviews:read", Grants.ITMO_SSO, Grants.ITMO_SSO))
        clock.advance(Duration.ofSeconds(2))
        val claim = assertIs<PairingClaim.Claimed>(pairings.claim(created.challengeId, created.claimSecret))
        assertEquals(id, claim.connection.id)
        assertContentEquals(BUNDLE, claim.sealedBundle)
        assertEquals(listOf(Grants.ITMO_SSO), claim.connection.itmoGrants)
        assertEquals(listOf(ConnectionScope.REVIEWS_READ), claim.connection.scopes.filter { it.granted }.map { it.scope.id })
        assertEquals(SID, claim.connection.itmo?.sid)
        assertEquals(setOf("reviews:read"), assertNotNull(auth.resolve(assertNotNull(claim.token))).scopes)

        // Without any ITMO grant: no sign-in, no bundle, no session.
        val widgetsOnly = pairings.create(
            createRequest("itmo-mcp", listOf(GrantRequest("widgets:links:read"), GrantRequest(Grants.ITMO_REFRESH))),
            IP,
        )
        pairings.open(phone, widgetsOnly.code, 2)
        pairings.approve(phone, widgetsOnly.challengeId, ApprovePairingRequest(null, null, null, listOf("widgets:links:read")))
        val bare = assertIs<PairingClaim.Claimed>(pairings.claim(widgetsOnly.challengeId, widgetsOnly.claimSecret))
        assertNull(bare.sealedBundle)
        assertNull(bare.connection.itmo)
        assertEquals(emptyList(), bare.connection.itmoGrants)
        assertNotNull(bare.token)

        val logged = logs.list.joinToString("\n") { it.formattedMessage }
        for (secret in listOf(
            created.code,
            created.claimSecret,
            BUNDLE_TEXT,
            SID,
        )) {
            assertFalse(logged.contains(secret), "Logs must not carry $secret")
        }
    }

    @Test
    fun `a client of kind app is always protocol 2 and has its own cap`() {
        val phone = user()
        registry.save(synthetic("synthetic-app").copy(maxPerUser = 1))
        val first = pairings.create(legacyRequest(ClientType.MCP).copy(client = null, clientId = "synthetic-app", scopes = null), IP)
        assertEquals(2, protocolOf(first))
        assertEquals("""[{"id":"widgets:links:read","required":false}]""", requestedOf(first))
        assertFailsWith<UpdateRequiredException> { pairings.open(phone, first.code) }
        assertEquals(ClientType.APP, pairings.open(phone, first.code, 2).client)
        pairings.approve(phone, first.challengeId, ApprovePairingRequest(null, null, null, listOf("widgets:links:read")))
        assertIs<PairingClaim.Claimed>(pairings.claim(first.challengeId, first.claimSecret))

        val second = pairings.create(createRequest("synthetic-app", listOf(GrantRequest("widgets:links:read"))), IP)
        assertFailsWith<BusinessRuleException> { pairings.open(phone, second.code, 2) }
    }

    @Test
    fun `a renewal asks for ITMO grants, shows which are held and replaces them only when sealed`() {
        val phone = user()
        val (token, id) = connect(
            phone,
            "itmo-mcp",
            listOf(GrantRequest("widgets:links:read"), GrantRequest(Grants.ITMO_REFRESH)),
            listOf("widgets:links:read", Grants.ITMO_REFRESH),
        )
        val client = assertNotNull(auth.resolve(token))
        for (grants in listOf(listOf(GrantRequest("widgets:links:read")), listOf(GrantRequest("itmo:refresh:another-client")))) {
            assertFailsWith<InvalidRequestDataException> {
                pairings.renew(client.connectionId, RenewChallengeRequest(COMMITMENT_TEXT, grants), IP)
            }
        }

        val renewal = pairings.renew(
            id,
            RenewChallengeRequest(COMMITMENT_TEXT, listOf(GrantRequest(Grants.ITMO_REFRESH), GrantRequest(Grants.ITMO_SSO))),
            IP,
        )
        assertEquals(2, protocolOf(renewal))
        assertFailsWith<UpdateRequiredException> { pairings.open(phone, renewal.code) }
        val preview = pairings.open(phone, renewal.code, 2)
        assertEquals(PairKind.RENEW, preview.kind)
        assertEquals(listOf(ConnectionScope.LINKS_READ), preview.requestedScopes.map { it.id })
        assertEquals(listOf(Grants.ITMO_REFRESH to true, Grants.ITMO_SSO to false), preview.grants?.map { it.id to it.granted })
        val renewed = ItmoSession(SID_2, NOW.plus(Duration.ofDays(90)))
        pairings.approve(
            phone,
            renewal.challengeId,
            ApprovePairingRequest(BUNDLE_TEXT, null, renewed, listOf(Grants.ITMO_REFRESH, Grants.ITMO_SSO)),
        )
        val claim = assertIs<PairingClaim.Claimed>(pairings.claim(renewal.challengeId, renewal.claimSecret))
        assertNull(claim.token)
        assertEquals(listOf(Grants.ITMO_REFRESH, Grants.ITMO_SSO), claim.connection.itmoGrants)
        assertEquals(renewed, claim.connection.itmo)
        assertEquals(listOf(ConnectionScope.LINKS_READ), claim.connection.scopes.filter { it.granted }.map { it.scope.id })

        // The user may turn every optional ITMO grant off: a fresh approval, the session stays as it was.
        clock.advance(Duration.ofMinutes(1))
        val declined = pairings.renew(id, RenewChallengeRequest(COMMITMENT_TEXT, listOf(GrantRequest(Grants.ITMO_SSO))), IP)
        pairings.open(phone, declined.code, 2)
        assertFailsWith<InvalidRequestDataException> {
            pairings.approve(phone, declined.challengeId, ApprovePairingRequest(BUNDLE_TEXT, null, renewed, emptyList()))
        }
        pairings.approve(phone, declined.challengeId, ApprovePairingRequest(null, null, null, emptyList()))
        val kept = assertIs<PairingClaim.Claimed>(pairings.claim(declined.challengeId, declined.claimSecret))
        assertEquals(listOf(Grants.ITMO_REFRESH, Grants.ITMO_SSO), kept.connection.itmoGrants)
        assertEquals(renewed, kept.connection.itmo)
        assertEquals(clock.instant(), kept.connection.approvedAt)

        // A released client renews with protocol 1: the v1 bundle holds the refresh token alone.
        clock.advance(Duration.ofMinutes(1))
        val legacy = pairings.renew(id, RenewChallengeRequest(COMMITMENT_TEXT), IP)
        assertEquals(1, protocolOf(legacy))
        assertEquals("""[{"id":"itmo:refresh:student-personal-cabinet","required":true}]""", requestedOf(legacy))
        pairings.open(phone, legacy.code)
        pairings.approve(phone, legacy.challengeId, ApprovePairingRequest(BUNDLE_TEXT, null, itmoSession()))
        assertEquals(
            listOf(Grants.ITMO_REFRESH),
            assertIs<PairingClaim.Claimed>(pairings.claim(legacy.challengeId, legacy.claimSecret)).connection.itmoGrants,
        )

        // The web asks for no ITMO grant.
        val (_, webId) = connect(phone, "web", null, listOf("widgets:user"))
        assertEquals("[]", requestedOf(pairings.renew(webId, RenewChallengeRequest(COMMITMENT_TEXT), IP)))
        assertFailsWith<InvalidRequestDataException> {
            pairings.renew(webId, RenewChallengeRequest(COMMITMENT_TEXT, listOf(GrantRequest(Grants.ITMO_REFRESH))), IP)
        }
    }

    @Test
    fun `the phone takes a live client's ITMO access back and the connection stays`() {
        val phone = user()
        val stranger = user()
        val (token, id) = connect(
            phone,
            "itmo-mcp",
            listOf(GrantRequest("widgets:links:read"), GrantRequest(Grants.ITMO_SSO)),
            listOf("widgets:links:read", Grants.ITMO_SSO),
        )
        assertTrue(auth.isClientSession(SID))
        assertFailsWith<NotFoundException> { connections.itmoClosed(stranger, id) }
        assertFailsWith<NotFoundException> { connections.itmoClosed(phone, UUID.randomUUID()) }

        connections.itmoClosed(phone, id)
        connections.itmoClosed(phone, id)
        val info = connections.get(id)
        assertEquals(ItmoStatus.CLOSED, info.itmoStatus)
        assertEquals(emptyList(), info.itmoGrants)
        assertNull(info.itmo)
        assertNull(info.revokedAt)
        assertNotNull(auth.resolve(token))
        assertFalse(auth.isClientSession(SID))
        assertEquals(
            listOf(null, null),
            jdbc.queryForMap("SELECT itmo_sid, itmo_expires_at FROM connections WHERE id = ?", id).values.toList(),
        )

        connections.revoke(phone, id)
        assertFailsWith<BusinessRuleException> { connections.itmoClosed(phone, id) }
    }

    private fun synthetic(id: String) = ConnectionClient(
        id, ClientType.APP, "Synthetic tool", "https://example.com", null, ClientDeployment.SELF_HOSTED, ClientAuth.PUBLIC,
        ClientStatus.ACTIVE, null,
        listOf(
            ClientGrant("widgets:links:read", required = false, requestedByDefault = true, purpose = null),
            ClientGrant(Grants.ITMO_REFRESH, required = false, requestedByDefault = false, purpose = "Синтетическая цель"),
        ),
        Instant.EPOCH, Instant.EPOCH,
    )

    private fun createRequest(clientId: String, grants: List<GrantRequest>?) =
        CreateChallengeRequest(PairKind.CONNECT, null, COMMITMENT_TEXT, "nas", "1.2.0", "linux", null, clientId, grants)

    private fun legacyRequest(client: ClientType, scopes: List<ConnectionScope>? = null) =
        CreateChallengeRequest(PairKind.CONNECT, client, COMMITMENT_TEXT, "nas", "1.2.0", "linux", scopes)

    private fun protocolOf(created: CreatedChallenge): Int = checkNotNull(
        jdbc.queryForObject("SELECT protocol FROM connection_pair_challenges WHERE id = ?", Int::class.java, created.challengeId),
    )

    /** The stored request, re-serialized without spaces. */
    private fun requestedOf(created: CreatedChallenge): String = checkNotNull(
        jdbc.queryForObject(
            "SELECT requested_grants::text FROM connection_pair_challenges WHERE id = ?",
            String::class.java,
            created.challengeId,
        ),
    ).replace(" ", "")

    private fun itmoSession() = ItmoSession(SID, clock.instant().plus(Duration.ofDays(90)))

    /** A claimed protocol-2 connection (protocol 1 when [grants] is null): its token and id. */
    private fun connect(phone: UUID, clientId: String, grants: List<GrantRequest>?, granted: List<String>): Pair<String, UUID> {
        val created = pairings.create(createRequest(clientId, grants), IP)
        pairings.open(phone, created.code, 2)
        val itmo = granted.any(Grants::isItmo)
        pairings.approve(
            phone,
            created.challengeId,
            ApprovePairingRequest(BUNDLE_TEXT.takeIf { itmo }, null, itmoSession().takeIf { itmo }, granted.takeIf { grants != null }),
        )
        val claim = assertIs<PairingClaim.Claimed>(pairings.claim(created.challengeId, created.claimSecret))
        return assertNotNull(claim.token) to claim.connection.id
    }

    private fun user(): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, isu, enrolled_at) VALUES (?, ?, now())", id, nextIsu.getAndIncrement())
        users.add(id)
        access.setAgentGranted(id, true)
        return id
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-27T09:00:00Z")
        const val IP = "198.51.100.79"
        const val SID = "8d1f0c2e-1b6a-4c55-9a43-0f2d7e6b1b01"
        const val SID_2 = "8d1f0c2e-1b6a-4c55-9a43-0f2d7e6b1b02"
        val COMMITMENT_TEXT = PairingService.encode(ByteArray(32) { it.toByte() })
        val BUNDLE = ByteArray(80) { (it * 5).toByte() }
        val BUNDLE_TEXT = PairingService.encode(BUNDLE)
        val nextIsu = AtomicInteger(978001)
        val KEY = TestKeys.publicKeyText(TestKeys.random())
        val OTHER_KEY = TestKeys.publicKeyText(TestKeys.random())
    }
}
