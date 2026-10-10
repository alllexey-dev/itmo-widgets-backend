package dev.alllexey.itmowidgets.backend.feature.credentials.persistence

import dev.alllexey.itmoapi.itmoid.TokenSet
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminUserSummaries
import dev.alllexey.itmowidgets.backend.feature.credentials.model.MyItmoTokenSnapshot
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoConfig
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoService
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialReplaced
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialStore
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import kotlin.time.toKotlinInstant

@TestPropertySource(properties = ["itmowidgets.my-itmo.refresh-token=synthetic-bootstrap"])
@Import(
    MyItmoService::class,
    ServiceCredentialStore::class,
    AdminAuditService::class,
    AdminAccess::class,
    AdminUserSummaries::class,
    ServiceCredentialStorePersistenceTest.TokenConfig::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ServiceCredentialStorePersistenceTest @Autowired constructor(
    private val store: ServiceCredentialStore,
    private val service: MyItmoService,
    private val clock: ControlledClock,
    private val replacements: RecordedReplacements,
    private val tokenEndpoint: TokenEndpoint,
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

        @Bean
        fun tokenEndpoint() = TokenEndpoint()

        @Bean
        fun myItmoEngine(tokenEndpoint: TokenEndpoint): HttpClientEngine = tokenEndpoint.engine
    }

    /** ITMO.ID's token endpoint: answers every refresh with [answer] and records the refresh tokens it got. */
    class TokenEndpoint {
        val refreshTokens = CopyOnWriteArrayList<String>()

        @Volatile var answer = ""

        val engine = MockEngine { request ->
            refreshTokens.add((request.body as FormDataContent).formData["refresh_token"].orEmpty())
            respond(answer, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
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
        tokenEndpoint.refreshTokens.clear()
        resetCredentials()
    }

    @AfterEach
    fun cleanup() {
        clock.clearPauses()
        jdbc.execute("DROP TRIGGER IF EXISTS token_test_reject_commit ON service_credentials")
        jdbc.execute("DROP FUNCTION IF EXISTS token_test_reject_commit()")
        resetCredentials()
        // Other classes share the database and count every user and audit row.
        jdbc.update("DELETE FROM admin_audit WHERE actor_id IN (SELECT id FROM users WHERE isu = ?)", ADMIN_ISU)
        jdbc.update("DELETE FROM users WHERE isu = ?", ADMIN_ISU)
    }

    @Test
    fun `the TokenStorage write commits the entire rotated bundle despite outer rollback`() {
        store.rotateMyItmo(tokens("old"))
        val rotated = tokens("rotated")

        assertFailsWith<IllegalStateException> {
            TransactionTemplate(manager).executeWithoutResult {
                runBlocking { service.write(rotated) }
                throw IllegalStateException("Synthetic failure after rotation")
            }
        }

        assertBundle(rotated, databaseSnapshot())
        assertBundle(rotated, store.myItmoSnapshot())
    }

    @Test
    fun `the TokenStorage read is the stored bundle, nothing without a refresh token, and a placeholder before the first refresh`() {
        assertNull(runBlocking { service.read() })

        store.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, "synthetic-seed")
        val seeded = runBlocking { service.read() }!!
        assertEquals("synthetic-seed", seeded.refreshToken)
        assertEquals(NOW.plus(Duration.ofDays(30)).toEpochMilli(), seeded.refreshExpiresAt.toEpochMilliseconds())
        // Expired, so the client refreshes before it sends the placeholder anywhere.
        assertEquals(0L, seeded.accessExpiresAt.toEpochMilliseconds())

        store.rotateMyItmo(tokens("rotated"))
        assertTokens(tokens("rotated"), runBlocking { service.read() }!!)
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
        val partial = tokens("partial")
        store.rotateMyItmo(partial)
        jdbc.update(
            "UPDATE service_credentials SET value = NULL, expires_at = NULL, status = 'MISSING' WHERE key = 'MY_ITMO_REFRESH_TOKEN'",
        )

        store.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, " synthetic-first-seed ")

        val stored = databaseSnapshot()
        assertEquals(partial.accessToken, stored.accessToken)
        assertEquals(partial.accessExpiresAt.toEpochMilliseconds(), stored.accessExpiresAt)
        assertEquals(partial.idToken, stored.idToken)
        assertEquals("synthetic-first-seed", stored.refreshToken)
        assertEquals(NOW.toEpochMilli() + Duration.ofDays(30).toMillis(), stored.refreshExpiresAt)
        assertEquals(CredentialRow("UNKNOWN", "SEED", null), row(ServiceCredential.MY_ITMO_REFRESH_TOKEN))
    }

    @Test
    fun `the Gemini seed fills only an empty row and never replaces a stored key`() {
        store.initializeFromBootstrap(ServiceCredential.GEMINI_API_KEY, " $GEMINI_KEY ")

        assertEquals(CredentialRow("UNKNOWN", "SEED", null), row(ServiceCredential.GEMINI_API_KEY))
        assertEquals(GEMINI_KEY, store.value(ServiceCredential.GEMINI_API_KEY))
        assertNull(store.state(ServiceCredential.GEMINI_API_KEY).expiresAt)

        val admin = admin()
        val replaced = "AIza" + "1".repeat(35)
        store.replace(ServiceCredential.GEMINI_API_KEY, replaced, admin)
        store.initializeFromBootstrap(ServiceCredential.GEMINI_API_KEY, GEMINI_KEY)

        assertEquals(replaced, store.value(ServiceCredential.GEMINI_API_KEY))
        assertEquals(CredentialRow("UNKNOWN", "ADMIN", null), row(ServiceCredential.GEMINI_API_KEY))
        assertEquals(MISSING_ROW, row(ServiceCredential.ISU_KEYCLOAK_IDENTITY))
    }

    @Test
    fun `stale bootstrap and repeated adapter initialization preserve the rotated bundle`() {
        store.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, "synthetic-old-seed")
        val rotated = tokens("rotated")
        runBlocking { service.write(rotated) }
        val oldClient = service.myItmo

        store.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, "synthetic-old-seed")
        service.onApplicationEvent(ContextRefreshedEvent(context))
        service.onApplicationEvent(ContextRefreshedEvent(context))

        assertNotSame(oldClient, service.myItmo)
        assertBundle(rotated, databaseSnapshot())
        assertEquals(rotated.refreshToken, runBlocking { service.read() }!!.refreshToken)
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
    fun `concurrent rotations and TokenStorage reads never expose a mixed bundle`() {
        val original = tokens("original")
        val firstBundle = tokens("first")
        val secondBundle = tokens("second")
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
            assertTokens(original, runBlocking { service.read() }!!)
            assertBundle(original, databaseSnapshot())

            firstPause.release()
            first.get(10, TimeUnit.SECONDS)
            secondPause.awaitEntered()
            assertTokens(firstBundle, runBlocking { service.read() }!!)
            assertBundle(firstBundle, databaseSnapshot())

            secondPause.release()
            second.get(10, TimeUnit.SECONDS)
            assertTokens(secondBundle, runBlocking { service.read() }!!)
            assertBundle(secondBundle, databaseSnapshot())
        } finally {
            firstPause.release()
            secondPause.release()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `snapshots are detached immutable values and their text does not expose credentials`() {
        store.rotateMyItmo(tokens("old"))
        val snapshot = store.myItmoSnapshot()
        store.rotateMyItmo(tokens("new"))

        assertBundle(tokens("old"), snapshot)
        assertBundle(tokens("new"), store.myItmoSnapshot())
        assertFalse(snapshot.toString().contains("synthetic"))
        assertEquals("MyItmoTokenSnapshot(redacted)", snapshot.toString())
    }

    @Test
    fun `SQL commit failure propagates from the TokenStorage write and leaves the old bundle intact`() {
        val original = tokens("original")
        store.rotateMyItmo(original)
        rejectTokenCommits()

        val failure = assertFailsWith<RuntimeException> { runBlocking { service.write(tokens("rejected")) } }

        assertCheckViolation(failure)
        assertBundle(original, databaseSnapshot())
        assertTokens(original, runBlocking { service.read() }!!)
    }

    @Test
    fun `a forced refresh stores the rotation ITMO_ID answered`() {
        val original = tokens("original")
        store.rotateMyItmo(original)
        tokenEndpoint.answer = ROTATED_ANSWER

        assertEquals("synthetic-access-rotated", runBlocking { service.myItmo.tokens.forceRefresh() })

        assertEquals(listOf(original.refreshToken), tokenEndpoint.refreshTokens)
        assertBundle(tokens("rotated"), databaseSnapshot())
    }

    @Test
    fun `a forced refresh fails when its commit fails and does not ask ITMO_ID again`() {
        val original = tokens("original")
        store.rotateMyItmo(original)
        tokenEndpoint.answer = ROTATED_ANSWER
        rejectTokenCommits()

        val failure = assertFailsWith<RuntimeException> { runBlocking { service.myItmo.tokens.forceRefresh() } }

        assertCheckViolation(failure)
        assertBundle(original, databaseSnapshot())
        assertEquals(listOf(original.refreshToken), tokenEndpoint.refreshTokens)
    }

    @Test
    fun `MyITMO rotation marks the refresh token used renewed and clears the last error`() {
        store.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, "synthetic-seed")
        service.recordAuthFailure("sport")
        assertEquals(CredentialRow("FAILED", "SEED", "AUTH sport"), row(ServiceCredential.MY_ITMO_REFRESH_TOKEN))

        store.rotateMyItmo(tokens("rotated"))

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
        assertEquals(
            (auditBefore + AuditRow("SERVICE_CREDENTIAL_REPLACED", "credential:ISU_KEYCLOAK_IDENTITY", null)).sortedBy {
                it.target
            },
            auditRows(admin),
        )
        assertEquals(listOf(ServiceCredentialReplaced(ServiceCredential.ISU_KEYCLOAK_IDENTITY)), replacements.events)
    }

    @Test
    fun `replacing the MyITMO refresh token clears the tokens it issued and other keys are refused`() {
        val admin = admin()
        store.rotateMyItmo(tokens("rotated"))

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
        store.rotateMyItmo(tokens("visible"))
        store.initializeFromBootstrap(ServiceCredential.ISU_KEYCLOAK_IDENTITY, "synthetic-cookie")
        store.initializeFromBootstrap(ServiceCredential.GEMINI_API_KEY, GEMINI_KEY)

        val states = store.states()

        assertEquals(5, states.size)
        assertEquals(ServiceCredential.entries, states.map { it.credential })
        assertTrue(states.all { it.present })
        assertFalse(states.toString().contains("synthetic"))
        assertFalse(states.toString().contains(GEMINI_KEY))
        TransactionTemplate(manager).executeWithoutResult {
            val entities = context.getBean(ServiceCredentialRepository::class.java).findAll()
            assertEquals(5, entities.size)
            entities.forEach { assertEquals("ServiceCredentialEntity(${it.key}, redacted)", it.toString()) }
        }
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
        "SELECT action, target, details FROM admin_audit WHERE actor_id = ? ORDER BY target",
        { rs, _ ->
            AuditRow(rs.getString(1), rs.getString(2), rs.getString(3))
        },
        admin,
    )

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

    private fun assertBundle(expected: TokenSet, actual: MyItmoTokenSnapshot) {
        assertEquals(expected.accessToken, actual.accessToken)
        assertEquals(expected.accessExpiresAt.toEpochMilliseconds(), actual.accessExpiresAt)
        assertEquals(expected.refreshToken, actual.refreshToken)
        assertEquals(expected.refreshExpiresAt.toEpochMilliseconds(), actual.refreshExpiresAt)
        assertEquals(expected.idToken, actual.idToken)
    }

    private fun assertTokens(expected: TokenSet, actual: TokenSet) {
        assertEquals(expected.accessToken, actual.accessToken)
        assertEquals(expected.accessExpiresAt, actual.accessExpiresAt)
        assertEquals(expected.refreshToken, actual.refreshToken)
        assertEquals(expected.refreshExpiresAt, actual.refreshExpiresAt)
        assertEquals(expected.idToken, actual.idToken)
    }

    private fun tokens(label: String) = TokenSet(
        "synthetic-access-$label",
        NOW.plusSeconds(3600).toKotlinInstant(),
        "synthetic-refresh-$label",
        NOW.plusSeconds(86400).toKotlinInstant(),
        "synthetic-id-$label",
    )

    companion object {
        private val NOW = Instant.parse("2026-09-08T21:00:00.123Z")
        private const val ADMIN_ISU = 962101
        private val MISSING_ROW = CredentialRow("MISSING", null, null)
        private const val ROTATED_ANSWER = """{"access_token":"synthetic-access-rotated","expires_in":3600,
            "refresh_token":"synthetic-refresh-rotated","refresh_expires_in":86400,"id_token":"synthetic-id-rotated"}"""

        /** Built from parts, so a search for leaked keys stays empty. */
        private val GEMINI_KEY = "AIza" + "0".repeat(35)
    }
}
