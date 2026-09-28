package dev.alllexey.itmowidgets.backend.repositories

import api.myitmo.model.other.TokenResponse
import api.myitmo.utils.AuthHelper
import api.myitmo.utils.TokenRefreshException
import dev.alllexey.itmowidgets.backend.configs.MyItmoConfig
import dev.alllexey.itmowidgets.backend.model.MyItmoTokenSnapshot
import dev.alllexey.itmowidgets.backend.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.services.AdminAccess
import dev.alllexey.itmowidgets.backend.services.AdminAuditService
import dev.alllexey.itmowidgets.backend.services.AdminUserSummaries
import dev.alllexey.itmowidgets.backend.services.MyItmoService
import dev.alllexey.itmowidgets.backend.services.ServiceCredentialReplaced
import dev.alllexey.itmowidgets.backend.services.ServiceCredentialStore
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@TestPropertySource(properties = ["itmowidgets.my-itmo.refresh-token=synthetic-bootstrap"])
@Import(
    MyItmoService::class, ServiceCredentialStore::class, AdminAuditService::class, AdminAccess::class, AdminUserSummaries::class,
    ServiceCredentialStorePersistenceTest.TokenConfig::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ServiceCredentialStorePersistenceTest @Autowired constructor(
    private val store: ServiceCredentialStore,
    private val service: MyItmoService,
    private val clock: ControlledClock,
    private val replacements: RecordedReplacements,
    private val jdbc: JdbcTemplate,
    private val manager: PlatformTransactionManager,
    private val context: ApplicationContext,
) : PostgreSqlRepositoryTest() {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MyItmoConfig::class)
    class TokenConfig {
        @Bean
        fun clock() = ControlledClock()

        @Bean
        fun replacements() = RecordedReplacements()
    }

    class RecordedReplacements {
        val events = CopyOnWriteArrayList<ServiceCredentialReplaced>()

        @EventListener
        fun on(event: ServiceCredentialReplaced) {
            events.add(event)
        }
    }

    class ControlledClock : Clock() {
        private val pauses = ConcurrentLinkedQueue<ClockPause>()

        override fun getZone(): ZoneId = ZoneId.of("Europe/Moscow")

        override fun withZone(zone: ZoneId): Clock = fixed(NOW, zone)

        // The store reads the time only while it holds its row locks.
        override fun instant(): Instant {
            pauses.poll()?.awaitRelease()
            return NOW
        }

        fun pauseNextRead() = ClockPause().also { pauses.add(it) }

        fun clearPauses() = pauses.clear()
    }

    class ClockPause {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun awaitRelease() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Timed out waiting for test transaction release" }
        }

        fun awaitEntered() {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Token transaction did not acquire its row lock")
        }

        fun release() = release.countDown()
    }

    @BeforeEach
    fun resetStorage() {
        clock.clearPauses()
        replacements.events.clear()
        resetCredentials()
    }

    @AfterEach
    fun cleanup() {
        clock.clearPauses()
        jdbc.execute("DROP TRIGGER IF EXISTS token_test_reject_commit ON service_credentials")
        jdbc.execute("DROP FUNCTION IF EXISTS token_test_reject_commit()")
        jdbc.update("DELETE FROM my_itmo_storage")
        resetCredentials()
        // Other classes share the database and count every user and audit row.
        jdbc.update("DELETE FROM admin_audit WHERE actor_id IN (SELECT id FROM users WHERE isu = ?)", ADMIN_ISU)
        jdbc.update("DELETE FROM users WHERE isu = ?", ADMIN_ISU)
    }

    @Test
    fun `real Storage callback commits the entire rotated bundle despite outer rollback`() {
        store.rotateMyItmo(response("old"))
        val rotated = response("rotated")

        assertFailsWith<IllegalStateException> {
            TransactionTemplate(manager).executeWithoutResult {
                service.myItmo.storage.update(rotated)
                throw IllegalStateException("Synthetic failure after rotation")
            }
        }

        assertBundle(rotated, databaseSnapshot())
        assertBundle(rotated, store.myItmoSnapshot())
    }

    @Test
    fun `all Storage setters persist separately even when their caller rolls back`() {
        store.rotateMyItmo(response("old"))
        val callback = service.myItmo.storage
        val setters = listOf<() -> Unit>(
            { callback.accessToken = "synthetic-set-access" },
            { callback.accessExpiresAt = 1001L },
            { callback.refreshToken = "synthetic-set-refresh" },
            { callback.refreshExpiresAt = 2002L },
            { callback.idToken = "synthetic-set-id" },
        )
        setters.forEach { setter ->
            assertFailsWith<IllegalStateException> {
                TransactionTemplate(manager).executeWithoutResult {
                    setter()
                    throw IllegalStateException("Synthetic caller rollback")
                }
            }
        }

        val stored = databaseSnapshot()
        assertEquals("synthetic-set-access", stored.accessToken)
        assertEquals(1001L, stored.accessExpiresAt)
        assertEquals("synthetic-set-refresh", stored.refreshToken)
        assertEquals(2002L, stored.refreshExpiresAt)
        assertEquals("synthetic-set-id", stored.idToken)
        assertEquals(stored.accessToken, callback.accessToken)
        assertEquals(stored.accessExpiresAt, callback.accessExpiresAt)
        assertEquals(stored.refreshToken, callback.refreshToken)
        assertEquals(stored.refreshExpiresAt, callback.refreshExpiresAt)
        assertEquals(stored.idToken, callback.idToken)
        assertEquals("ROTATION", row(ServiceCredential.MY_ITMO_REFRESH_TOKEN).source)

        callback.accessToken = null
        callback.refreshToken = null
        callback.idToken = null
        assertNull(databaseSnapshot().accessToken)
        assertNull(databaseSnapshot().refreshToken)
        assertNull(databaseSnapshot().idToken)
        ServiceCredential.MY_ITMO.forEach { assertEquals(MISSING_ROW, row(it)) }
    }

    @Test
    fun `read of empty rows is an empty snapshot and does not write from the read only transaction`() {
        val before = updatedAt()

        val snapshot = store.myItmoSnapshot()

        assertNull(snapshot.accessToken)
        assertEquals(0L, snapshot.accessExpiresAt)
        assertNull(snapshot.refreshToken)
        assertEquals(0L, snapshot.refreshExpiresAt)
        assertNull(snapshot.idToken)
        assertNull(store.value(ServiceCredential.ISU_KEYCLOAK_IDENTITY))
        assertEquals(before, updatedAt())
    }

    @Test
    fun `blank bootstrap writes nothing`() {
        val before = updatedAt()

        store.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, null)
        store.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, " ")
        store.initializeFromBootstrap(ServiceCredential.ISU_KEYCLOAK_IDENTITY, "")

        assertEquals(before, updatedAt())
        ServiceCredential.entries.forEach { assertEquals(MISSING_ROW, row(it)) }
    }

    @Test
    fun `bootstrap fills missing refresh token without replacing existing access and id token`() {
        val partial = response("partial").apply { refreshToken = null }
        store.rotateMyItmo(partial)

        store.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, " synthetic-first-seed ")

        val stored = databaseSnapshot()
        assertEquals(partial.accessToken, stored.accessToken)
        assertEquals(NOW.toEpochMilli() + partial.expiresIn * 1000L, stored.accessExpiresAt)
        assertEquals(partial.idToken, stored.idToken)
        assertEquals("synthetic-first-seed", stored.refreshToken)
        assertEquals(NOW.toEpochMilli() + Duration.ofDays(30).toMillis(), stored.refreshExpiresAt)
        assertEquals(CredentialRow("UNKNOWN", "SEED", null), row(ServiceCredential.MY_ITMO_REFRESH_TOKEN))
    }

    @Test
    fun `stale bootstrap and repeated adapter initialization preserve the rotated bundle`() {
        store.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, "synthetic-old-seed")
        val rotated = response("rotated")
        service.myItmo.storage.update(rotated)
        val oldClient = service.myItmo

        store.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, "synthetic-old-seed")
        service.onApplicationEvent(ContextRefreshedEvent(context))
        service.onApplicationEvent(ContextRefreshedEvent(context))

        assertNotSame(oldClient, service.myItmo)
        assertBundle(rotated, databaseSnapshot())
        assertEquals(rotated.refreshToken, service.myItmo.storage.refreshToken)
        assertEquals("ROTATION", row(ServiceCredential.MY_ITMO_REFRESH_TOKEN).source)
    }

    @Test
    fun `concurrent bootstrap writes the seed once`() {
        val pause = clock.pauseNextRead()
        val secondStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { store.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, "synthetic-first-seed") }
            pause.awaitEntered()
            val second = executor.submit {
                secondStarted.countDown()
                store.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, "synthetic-second-seed")
            }
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS))
            pause.release()
            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS)

            assertEquals("synthetic-first-seed", databaseSnapshot().refreshToken)
            assertEquals(NOW.toEpochMilli() + Duration.ofDays(30).toMillis(), databaseSnapshot().refreshExpiresAt)
            assertEquals(CredentialRow("UNKNOWN", "SEED", null), row(ServiceCredential.MY_ITMO_REFRESH_TOKEN))
        } finally {
            pause.release()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `concurrent rotations and toTokenResponse never expose a mixed bundle`() {
        val original = response("original")
        val firstBundle = response("first")
        val secondBundle = response("second")
        store.rotateMyItmo(original)
        val firstPause = clock.pauseNextRead()
        val secondPause = clock.pauseNextRead()
        val secondStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { store.rotateMyItmo(firstBundle) }
            firstPause.awaitEntered()
            val second = executor.submit {
                secondStarted.countDown()
                store.rotateMyItmo(secondBundle)
            }
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS))
            assertTokenResponse(original, service.toTokenResponse())
            assertBundle(original, databaseSnapshot())

            firstPause.release()
            first.get(10, TimeUnit.SECONDS)
            secondPause.awaitEntered()
            assertTokenResponse(firstBundle, service.toTokenResponse())
            assertBundle(firstBundle, databaseSnapshot())

            secondPause.release()
            second.get(10, TimeUnit.SECONDS)
            assertTokenResponse(secondBundle, service.toTokenResponse())
            assertBundle(secondBundle, databaseSnapshot())
        } finally {
            firstPause.release()
            secondPause.release()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `expiry calculation uses fixed milliseconds and long arithmetic`() {
        val bundle = response("long-expiry").apply {
            expiresIn = Int.MAX_VALUE.toLong() + 1L
            refreshExpiresIn = Int.MAX_VALUE.toLong() + 2L
        }
        store.rotateMyItmo(bundle)
        assertBundle(bundle, databaseSnapshot())
    }

    @Test
    fun `snapshots are detached immutable values and their text does not expose credentials`() {
        store.rotateMyItmo(response("old"))
        val snapshot = store.myItmoSnapshot()
        store.rotateMyItmo(response("new"))

        assertBundle(response("old"), snapshot)
        assertBundle(response("new"), store.myItmoSnapshot())
        assertFalse(snapshot.toString().contains("synthetic"))
        assertEquals("MyItmoTokenSnapshot(redacted)", snapshot.toString())
    }

    @Test
    fun `SQL commit failure propagates from real callback and leaves the old bundle intact`() {
        val original = response("original")
        store.rotateMyItmo(original)
        rejectTokenCommits()

        val failure = assertFailsWith<RuntimeException> {
            service.myItmo.storage.update(response("rejected"))
        }

        assertCheckViolation(failure)
        assertBundle(original, databaseSnapshot())
        assertTokenResponse(original, service.toTokenResponse())
    }

    @Test
    fun `forceRefreshTokens saves the mocked upstream response without network`() {
        val original = response("original")
        val rotated = response("rotated")
        store.rotateMyItmo(original)
        val helper = mock(AuthHelper::class.java)
        `when`(helper.refreshTokens(original.refreshToken)).thenReturn(rotated)
        service.myItmo.setAuthHelper(helper)

        val actual = service.myItmo.forceRefreshTokens()

        assertTokenResponse(rotated, actual)
        assertBundle(rotated, databaseSnapshot())
        verify(helper, times(1)).refreshTokens(original.refreshToken)
    }

    @Test
    fun `forceRefreshTokens fails when persistence commit fails and does not retry OAuth`() {
        val original = response("original")
        store.rotateMyItmo(original)
        val helper = mock(AuthHelper::class.java)
        `when`(helper.refreshTokens(original.refreshToken)).thenReturn(response("rejected"))
        service.myItmo.setAuthHelper(helper)
        rejectTokenCommits()

        val failure = assertFailsWith<TokenRefreshException> { service.myItmo.forceRefreshTokens() }

        assertCheckViolation(failure)
        assertBundle(original, databaseSnapshot())
        verify(helper, times(1)).refreshTokens(original.refreshToken)
    }

    @Test
    fun `MyITMO rotation marks the refresh token used renewed and clears the last error`() {
        store.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, "synthetic-seed")
        service.recordAuthFailure("sport")
        assertEquals(CredentialRow("FAILED", "SEED", "AUTH sport"), row(ServiceCredential.MY_ITMO_REFRESH_TOKEN))

        store.rotateMyItmo(response("rotated"))

        val refresh = store.state(ServiceCredential.MY_ITMO_REFRESH_TOKEN)
        assertEquals(ServiceCredentialStatus.OK, refresh.status)
        assertEquals(NOW, refresh.lastUsedAt)
        assertEquals(NOW, refresh.lastRenewedAt)
        assertNull(refresh.lastError)
        assertNull(refresh.updatedBy)
        assertEquals(CredentialRow("OK", "ROTATION", null), row(ServiceCredential.MY_ITMO_REFRESH_TOKEN))
    }

    @Test
    fun `renewal stores a rotated value only when there is one`() {
        store.initializeFromBootstrap(ServiceCredential.ISU_KEYCLOAK_IDENTITY, "synthetic-cookie")
        assertEquals(CredentialRow("UNKNOWN", "SEED", null), row(ServiceCredential.ISU_KEYCLOAK_IDENTITY))
        assertNull(store.state(ServiceCredential.ISU_KEYCLOAK_IDENTITY).expiresAt)

        store.recordRenewal(ServiceCredential.ISU_KEYCLOAK_IDENTITY, null, null)

        var state = store.state(ServiceCredential.ISU_KEYCLOAK_IDENTITY)
        assertEquals(CredentialRow("OK", "SEED", null), row(ServiceCredential.ISU_KEYCLOAK_IDENTITY))
        assertEquals(NOW, state.lastUsedAt)
        assertEquals(NOW, state.lastRenewedAt)
        assertEquals("synthetic-cookie", store.value(ServiceCredential.ISU_KEYCLOAK_IDENTITY))

        val expiresAt = NOW.plus(Duration.ofDays(90))
        store.recordRenewal(ServiceCredential.ISU_KEYCLOAK_IDENTITY, "synthetic-cookie-rotated", expiresAt)

        state = store.state(ServiceCredential.ISU_KEYCLOAK_IDENTITY)
        assertEquals(CredentialRow("OK", "ROTATION", null), row(ServiceCredential.ISU_KEYCLOAK_IDENTITY))
        assertEquals(expiresAt, state.expiresAt)
        assertEquals("synthetic-cookie-rotated", store.value(ServiceCredential.ISU_KEYCLOAK_IDENTITY))
    }

    @Test
    fun `use and failures record status and error while a row without value stays missing`() {
        store.initializeFromBootstrap(ServiceCredential.ISU_KEYCLOAK_IDENTITY, "synthetic-cookie")

        store.recordUse(ServiceCredential.ISU_KEYCLOAK_IDENTITY)
        assertEquals(CredentialRow("OK", "SEED", null), row(ServiceCredential.ISU_KEYCLOAK_IDENTITY))
        assertEquals(NOW, store.state(ServiceCredential.ISU_KEYCLOAK_IDENTITY).lastUsedAt)

        store.recordFailure(ServiceCredential.ISU_KEYCLOAK_IDENTITY, ServiceCredentialStatus.EXPIRED, "EXPIRED login")
        assertEquals(CredentialRow("EXPIRED", "SEED", "EXPIRED login"), row(ServiceCredential.ISU_KEYCLOAK_IDENTITY))
        assertEquals(NOW, store.state(ServiceCredential.ISU_KEYCLOAK_IDENTITY).lastErrorAt)

        store.recordFailure(ServiceCredential.ISU_KEYCLOAK_IDENTITY, ServiceCredentialStatus.FAILED, "HTTP 503 members/93724")
        assertEquals(CredentialRow("FAILED", "SEED", "HTTP 503 members/93724"), row(ServiceCredential.ISU_KEYCLOAK_IDENTITY))

        store.recordUse(ServiceCredential.MY_ITMO_ID_TOKEN)
        store.recordFailure(ServiceCredential.MY_ITMO_ID_TOKEN, ServiceCredentialStatus.FAILED, "x".repeat(400))
        assertEquals(CredentialRow("MISSING", null, "x".repeat(300)), row(ServiceCredential.MY_ITMO_ID_TOKEN))
        assertNull(store.state(ServiceCredential.MY_ITMO_ID_TOKEN).lastUsedAt)
        assertFailsWith<IllegalArgumentException> {
            store.recordFailure(ServiceCredential.ISU_KEYCLOAK_IDENTITY, ServiceCredentialStatus.OK, "OK")
        }
    }

    @Test
    fun `admin replacement of the ISU cookie is audited once and announced`() {
        val admin = admin()
        store.initializeFromBootstrap(ServiceCredential.ISU_KEYCLOAK_IDENTITY, "synthetic-cookie")
        store.recordFailure(ServiceCredential.ISU_KEYCLOAK_IDENTITY, ServiceCredentialStatus.EXPIRED, "EXPIRED login")
        val auditBefore = auditRows(admin)

        store.replace(ServiceCredential.ISU_KEYCLOAK_IDENTITY, "synthetic-admin-cookie", admin)

        val state = store.state(ServiceCredential.ISU_KEYCLOAK_IDENTITY)
        assertEquals(ServiceCredentialStatus.UNKNOWN, state.status)
        assertEquals(admin, state.updatedBy)
        assertNull(state.lastError)
        assertNull(state.lastErrorAt)
        assertNull(state.expiresAt)
        assertEquals(CredentialRow("UNKNOWN", "ADMIN", null), row(ServiceCredential.ISU_KEYCLOAK_IDENTITY))
        assertEquals("synthetic-admin-cookie", store.value(ServiceCredential.ISU_KEYCLOAK_IDENTITY))
        assertEquals((auditBefore + AuditRow("SERVICE_CREDENTIAL_REPLACED", "credential:ISU_KEYCLOAK_IDENTITY", null)).sortedBy { it.target },
            auditRows(admin))
        assertEquals(listOf(ServiceCredentialReplaced(ServiceCredential.ISU_KEYCLOAK_IDENTITY)), replacements.events)
    }

    @Test
    fun `replacing the MyITMO refresh token clears the tokens it issued and other keys are refused`() {
        val admin = admin()
        store.rotateMyItmo(response("rotated"))

        store.replace(ServiceCredential.MY_ITMO_REFRESH_TOKEN, "synthetic-admin-refresh", admin)

        val snapshot = databaseSnapshot()
        assertEquals("synthetic-admin-refresh", snapshot.refreshToken)
        assertEquals(NOW.toEpochMilli() + Duration.ofDays(30).toMillis(), snapshot.refreshExpiresAt)
        assertEquals(CredentialRow("UNKNOWN", "ADMIN", null), row(ServiceCredential.MY_ITMO_REFRESH_TOKEN))
        assertEquals(MISSING_ROW, row(ServiceCredential.MY_ITMO_ACCESS_TOKEN))
        assertEquals(MISSING_ROW, row(ServiceCredential.MY_ITMO_ID_TOKEN))
        assertNull(store.state(ServiceCredential.MY_ITMO_ACCESS_TOKEN).updatedBy)
        assertNull(store.state(ServiceCredential.MY_ITMO_ACCESS_TOKEN).expiresAt)

        val before = updatedAt()
        val auditBefore = auditRows(admin)
        assertFailsWith<IllegalArgumentException> { store.replace(ServiceCredential.MY_ITMO_ACCESS_TOKEN, "synthetic-access", admin) }
        assertFailsWith<IllegalArgumentException> { store.initializeFromBootstrap(ServiceCredential.MY_ITMO_ID_TOKEN, "synthetic-id") }
        assertEquals(before, updatedAt())
        assertEquals(auditBefore, auditRows(admin))
        assertEquals(listOf(ServiceCredentialReplaced(ServiceCredential.MY_ITMO_REFRESH_TOKEN)), replacements.events)
    }

    @Test
    fun `states and entity text never contain values`() {
        store.rotateMyItmo(response("visible"))
        store.initializeFromBootstrap(ServiceCredential.ISU_KEYCLOAK_IDENTITY, "synthetic-cookie")

        val states = store.states()

        assertEquals(ServiceCredential.entries, states.map { it.credential })
        assertTrue(states.all { it.present })
        assertFalse(states.toString().contains("synthetic"))
        TransactionTemplate(manager).executeWithoutResult {
            val entities = context.getBean(ServiceCredentialRepository::class.java).findAll()
            assertEquals(4, entities.size)
            entities.forEach { assertEquals("ServiceCredentialEntity(${it.key}, redacted)", it.toString()) }
        }
    }

    @Test
    fun `store neither reads nor writes the legacy MyITMO table`() {
        jdbc.update(
            "INSERT INTO my_itmo_storage (id, refresh_token, refresh_token_expires_at, access_token, access_token_expires_at, id_token) " +
                "VALUES (1, 'synthetic-legacy-refresh', 1790000000123, 'synthetic-legacy-access', 1790000000456, 'synthetic-legacy-id')",
        )
        val legacy = legacyRow()

        val snapshot = store.myItmoSnapshot()
        assertNull(snapshot.accessToken)
        assertNull(snapshot.refreshToken)
        assertNull(snapshot.idToken)

        store.rotateMyItmo(response("rotated"))
        store.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, "synthetic-seed")
        store.replace(ServiceCredential.MY_ITMO_REFRESH_TOKEN, "synthetic-admin-refresh", admin())

        assertEquals(legacy, legacyRow())
    }

    private data class CredentialRow(val status: String, val source: String?, val lastError: String?)

    private data class AuditRow(val action: String, val target: String, val details: String?)

    private fun row(credential: ServiceCredential): CredentialRow = jdbc.queryForObject(
        "SELECT status, updated_source, last_error FROM service_credentials WHERE key = ?",
        { rs, _ -> CredentialRow(rs.getString(1), rs.getString(2), rs.getString(3)) },
        credential.name,
    )!!

    private fun updatedAt(): Map<String, OffsetDateTime> = jdbc.query("SELECT key, updated_at FROM service_credentials") { rs, _ ->
        rs.getString(1) to rs.getObject(2, OffsetDateTime::class.java)
    }.toMap()

    /** Rows of every test in this class share one fixed time, so only their multiset is stable. */
    private fun auditRows(admin: UUID): List<AuditRow> = jdbc.query(
        "SELECT action, target, details FROM admin_audit WHERE actor_id = ? ORDER BY target", { rs, _ ->
            AuditRow(rs.getString(1), rs.getString(2), rs.getString(3))
        }, admin,
    )

    private fun legacyRow(): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM my_itmo_storage WHERE id = 1")

    /** Committed, because the store audits in its own transaction; removed with its audit rows after each test. */
    private fun admin(): UUID = TransactionTemplate(manager).execute {
        jdbc.queryForList("SELECT id FROM users WHERE isu = ?", UUID::class.java, ADMIN_ISU).firstOrNull() ?: UUID.randomUUID().also { id ->
            jdbc.update("INSERT INTO users (id, isu, name) VALUES (?, ?, 'Synthetic admin')", id, ADMIN_ISU)
            jdbc.update("INSERT INTO user_settings (user_id) VALUES (?)", id)
        }
    }!!

    private fun resetCredentials() {
        jdbc.update(
            """
            UPDATE service_credentials SET value = NULL, expires_at = NULL, status = 'MISSING', last_used_at = NULL,
                last_renewed_at = NULL, last_error_at = NULL, last_error = NULL, updated_by = NULL, updated_source = NULL
            """.trimIndent(),
        )
    }

    private fun rejectTokenCommits() {
        // Deferred failure proves the proxy must commit before acknowledging the callback.
        jdbc.execute(
            """
            CREATE FUNCTION token_test_reject_commit() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'Synthetic credential commit rejection' USING ERRCODE = '23514';
            END;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE CONSTRAINT TRIGGER token_test_reject_commit
            AFTER UPDATE ON service_credentials DEFERRABLE INITIALLY DEFERRED
            FOR EACH ROW EXECUTE FUNCTION token_test_reject_commit()
            """.trimIndent(),
        )
    }

    private fun assertCheckViolation(failure: Throwable) {
        assertTrue(generateSequence(failure) { it.cause }.any { it is SQLException && it.sqlState == "23514" })
    }

    private fun databaseSnapshot(): MyItmoTokenSnapshot {
        val rows = jdbc.query("SELECT key, value, expires_at FROM service_credentials") { rs, _ ->
            rs.getString(1) to Pair(rs.getString(2), rs.getObject(3, OffsetDateTime::class.java)?.toInstant()?.toEpochMilli() ?: 0L)
        }.toMap()
        return MyItmoTokenSnapshot(
            rows.getValue("MY_ITMO_ACCESS_TOKEN").first,
            rows.getValue("MY_ITMO_ACCESS_TOKEN").second,
            rows.getValue("MY_ITMO_REFRESH_TOKEN").first,
            rows.getValue("MY_ITMO_REFRESH_TOKEN").second,
            rows.getValue("MY_ITMO_ID_TOKEN").first,
        )
    }

    private fun assertBundle(expected: TokenResponse, actual: MyItmoTokenSnapshot) {
        assertEquals(expected.accessToken, actual.accessToken)
        assertEquals(NOW.toEpochMilli() + expected.expiresIn * 1000L, actual.accessExpiresAt)
        assertEquals(expected.refreshToken, actual.refreshToken)
        assertEquals(NOW.toEpochMilli() + expected.refreshExpiresIn * 1000L, actual.refreshExpiresAt)
        assertEquals(expected.idToken, actual.idToken)
    }

    private fun assertTokenResponse(expected: TokenResponse, actual: TokenResponse) {
        assertEquals(expected.accessToken, actual.accessToken)
        assertEquals(expected.refreshToken, actual.refreshToken)
        assertEquals(expected.idToken, actual.idToken)
    }

    private fun response(label: String) = TokenResponse().apply {
        accessToken = "synthetic-access-$label"
        refreshToken = "synthetic-refresh-$label"
        idToken = "synthetic-id-$label"
        expiresIn = 3600L
        refreshExpiresIn = 86400L
    }

    companion object {
        private val NOW = Instant.parse("2026-09-08T21:00:00.123Z")
        private const val ADMIN_ISU = 962101
        private val MISSING_ROW = CredentialRow("MISSING", null, null)
    }
}
