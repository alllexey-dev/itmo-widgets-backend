package dev.alllexey.itmowidgets.backend.repositories

import api.myitmo.model.other.TokenResponse
import api.myitmo.utils.AuthHelper
import api.myitmo.utils.TokenRefreshException
import dev.alllexey.itmowidgets.backend.configs.MyItmoConfig
import dev.alllexey.itmowidgets.backend.model.MyItmoTokenSnapshot
import dev.alllexey.itmowidgets.backend.services.MyItmoService
import dev.alllexey.itmowidgets.backend.services.MyItmoTokenStore
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@TestPropertySource(properties = ["itmowidgets.my-itmo.refresh-token=synthetic-bootstrap"])
@Import(MyItmoService::class, MyItmoTokenStore::class, MyItmoTokenStorePersistenceTest.TokenConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MyItmoTokenStorePersistenceTest @Autowired constructor(
    private val store: MyItmoTokenStore,
    private val service: MyItmoService,
    private val clock: ControlledClock,
    private val jdbc: JdbcTemplate,
    private val manager: PlatformTransactionManager,
    private val context: ApplicationContext,
) : PostgreSqlRepositoryTest() {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MyItmoConfig::class)
    class TokenConfig {
        @Bean
        fun clock() = ControlledClock()
    }

    class ControlledClock : Clock() {
        private val pauses = ConcurrentLinkedQueue<ClockPause>()

        override fun getZone(): ZoneId = ZoneId.of("Europe/Moscow")

        override fun withZone(zone: ZoneId): Clock = fixed(NOW, zone)

        override fun instant(): Instant = NOW

        override fun millis(): Long {
            pauses.poll()?.awaitRelease()
            return NOW.toEpochMilli()
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
        jdbc.update("DELETE FROM my_itmo_storage")
    }

    @AfterEach
    fun cleanup() {
        clock.clearPauses()
        jdbc.execute("DROP TRIGGER IF EXISTS token_test_reject_commit ON my_itmo_storage")
        jdbc.execute("DROP FUNCTION IF EXISTS token_test_reject_commit()")
        jdbc.update("DELETE FROM my_itmo_storage")
    }

    @Test
    fun `real Storage callback commits the entire rotated bundle despite outer rollback`() {
        store.update(response("old"))
        val rotated = response("rotated")

        assertFailsWith<IllegalStateException> {
            TransactionTemplate(manager).executeWithoutResult {
                service.myItmo.storage.update(rotated)
                throw IllegalStateException("Synthetic failure after rotation")
            }
        }

        assertBundle(rotated, databaseSnapshot())
        assertBundle(rotated, store.readSnapshot())
    }

    @Test
    fun `all Storage setters persist separately even when their caller rolls back`() {
        store.update(response("old"))
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

        callback.accessToken = null
        callback.refreshToken = null
        callback.idToken = null
        assertNull(databaseSnapshot().accessToken)
        assertNull(databaseSnapshot().refreshToken)
        assertNull(databaseSnapshot().idToken)
    }

    @Test
    fun `read of absent storage is empty and does not write from the read only transaction`() {
        val snapshot = store.readSnapshot()
        assertNull(snapshot.accessToken)
        assertEquals(0L, snapshot.accessExpiresAt)
        assertNull(snapshot.refreshToken)
        assertEquals(0L, snapshot.refreshExpiresAt)
        assertNull(snapshot.idToken)
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM my_itmo_storage", Long::class.java))
    }

    @Test
    fun `blank bootstrap creates singleton without inventing credentials`() {
        store.initializeFromBootstrap(null)
        store.initializeFromBootstrap(" ")
        val stored = databaseSnapshot()
        assertNull(stored.refreshToken)
        assertEquals(0L, stored.refreshExpiresAt)
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM my_itmo_storage", Long::class.java))
    }

    @Test
    fun `bootstrap fills missing refresh token without replacing existing access and id token`() {
        val partial = response("partial").apply { refreshToken = null }
        store.update(partial)

        store.initializeFromBootstrap("synthetic-first-seed")

        val stored = databaseSnapshot()
        assertEquals(partial.accessToken, stored.accessToken)
        assertEquals(NOW.toEpochMilli() + partial.expiresIn * 1000L, stored.accessExpiresAt)
        assertEquals(partial.idToken, stored.idToken)
        assertEquals("synthetic-first-seed", stored.refreshToken)
        assertEquals(NOW.toEpochMilli() + Duration.ofDays(30).toMillis(), stored.refreshExpiresAt)
    }

    @Test
    fun `stale bootstrap and repeated adapter initialization preserve the rotated bundle`() {
        store.initializeFromBootstrap("synthetic-old-seed")
        val rotated = response("rotated")
        service.myItmo.storage.update(rotated)
        val oldClient = service.myItmo

        store.initializeFromBootstrap("synthetic-old-seed")
        service.onApplicationEvent(ContextRefreshedEvent(context))
        service.onApplicationEvent(ContextRefreshedEvent(context))

        assertNotSame(oldClient, service.myItmo)
        assertBundle(rotated, databaseSnapshot())
        assertEquals(rotated.refreshToken, service.myItmo.storage.refreshToken)
    }

    @Test
    fun `concurrent first bootstrap creates one singleton and preserves the winning seed`() {
        val pause = clock.pauseNextRead()
        val secondStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { store.initializeFromBootstrap("synthetic-first-seed") }
            pause.awaitEntered()
            val second = executor.submit {
                secondStarted.countDown()
                store.initializeFromBootstrap("synthetic-second-seed")
            }
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS))
            pause.release()
            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS)

            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM my_itmo_storage", Long::class.java))
            assertEquals("synthetic-first-seed", databaseSnapshot().refreshToken)
            assertEquals(NOW.toEpochMilli() + Duration.ofDays(30).toMillis(), databaseSnapshot().refreshExpiresAt)
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
        store.update(original)
        val firstPause = clock.pauseNextRead()
        val secondPause = clock.pauseNextRead()
        val secondStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { store.update(firstBundle) }
            firstPause.awaitEntered()
            val second = executor.submit {
                secondStarted.countDown()
                store.update(secondBundle)
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
        store.update(bundle)
        assertBundle(bundle, databaseSnapshot())
    }

    @Test
    fun `snapshots are detached immutable values and their text does not expose credentials`() {
        store.update(response("old"))
        val snapshot = store.readSnapshot()
        store.update(response("new"))

        assertBundle(response("old"), snapshot)
        assertBundle(response("new"), store.readSnapshot())
        assertFalse(snapshot.toString().contains("synthetic"))
        assertEquals("MyItmoTokenSnapshot(redacted)", snapshot.toString())
    }

    @Test
    fun `SQL commit failure propagates from real callback and leaves the old bundle intact`() {
        val original = response("original")
        store.update(original)
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
        store.update(original)
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
        store.update(original)
        val helper = mock(AuthHelper::class.java)
        `when`(helper.refreshTokens(original.refreshToken)).thenReturn(response("rejected"))
        service.myItmo.setAuthHelper(helper)
        rejectTokenCommits()

        val failure = assertFailsWith<TokenRefreshException> { service.myItmo.forceRefreshTokens() }

        assertCheckViolation(failure)
        assertBundle(original, databaseSnapshot())
        verify(helper, times(1)).refreshTokens(original.refreshToken)
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
            AFTER UPDATE ON my_itmo_storage DEFERRABLE INITIALLY DEFERRED
            FOR EACH ROW EXECUTE FUNCTION token_test_reject_commit()
            """.trimIndent(),
        )
    }

    private fun assertCheckViolation(failure: Throwable) {
        assertTrue(generateSequence(failure) { it.cause }.any { it is SQLException && it.sqlState == "23514" })
    }

    private fun databaseSnapshot(): MyItmoTokenSnapshot = jdbc.queryForObject(
        "SELECT access_token, access_token_expires_at, refresh_token, refresh_token_expires_at, id_token FROM my_itmo_storage WHERE id = 1",
    ) { row, _ ->
        MyItmoTokenSnapshot(
            row.getString("access_token"),
            row.getLong("access_token_expires_at"),
            row.getString("refresh_token"),
            row.getLong("refresh_token_expires_at"),
            row.getString("id_token"),
        )
    }!!

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
    }
}
