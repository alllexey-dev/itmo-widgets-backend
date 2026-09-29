package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.configs.IsuConfig
import dev.alllexey.itmowidgets.backend.model.CredentialSource
import dev.alllexey.itmowidgets.backend.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.repositories.PostgreSqlRepositoryTest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager

/** The stores commit on their own, so this class runs without a test transaction and cleans up after itself. */
@Import(IsuVerificationService::class, IsuPotokCache::class, ReviewVerificationStore::class, ServiceCredentialStore::class,
    AdminAuditService::class, AdminAccess::class, AdminUserSummaries::class, IsuVerificationServiceTest.TestConfig::class)
// A @Bean of a @ConfigurationProperties class would be rebound, so the test binds its values instead.
@TestPropertySource(properties = ["itmowidgets.isu.request-delay=0ms"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IsuVerificationServiceTest @Autowired constructor(
    private val service: IsuVerificationService,
    private val store: ReviewVerificationStore,
    private val cache: IsuPotokCache,
    private val credentials: ServiceCredentialStore,
    private val isu: FakeIsuClient,
    private val clock: WebLoginServiceTest.MutableClock,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IsuConfig::class)
    class TestConfig {
        @Bean fun fakeIsuClient() = FakeIsuClient()
        @Bean fun isuExecutor(): TaskExecutor = SyncTaskExecutor()
        @Bean fun clock() = WebLoginServiceTest.MutableClock()
    }

    /** In-memory ISU: flow teachers and members by flow number, scripted failures and a call log. */
    class FakeIsuClient : IsuClient {
        val teachers = mutableMapOf<Long, Set<Int>>()
        val members = mutableMapOf<Long, Set<Int>>()
        var loginFailure: IsuErrorCategory? = null
        var rotated: String? = null
        var rotatedExpiresAt: Instant? = null
        /** Requests by `members/<id>` or `teachers/<id>` that fail every time. */
        val failAlways = mutableMapOf<String, IsuErrorCategory>()
        /** Requests that fail once. */
        val failOnce = mutableMapOf<String, IsuErrorCategory>()
        /** Runs before a request is answered, like a user acting during the check. */
        var beforeRequest: (String) -> Unit = {}
        val calls = CopyOnWriteArrayList<String>()
        val identities = CopyOnWriteArrayList<String>()
        private var sessions = 0

        override fun login(identity: String): IsuSession {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "ISU request inside a transaction")
            calls.add("login")
            identities.add(identity)
            loginFailure?.let { throw IsuFailure(it, "login") }
            return IsuSession("${++sessions}", rotated, rotatedExpiresAt)
        }

        override fun members(session: IsuSession, potokId: Long, date: LocalDate): Set<Int> =
            answer("members/$potokId") { members[potokId].orEmpty() }

        override fun teachers(session: IsuSession, potokId: Long): Set<Int> =
            answer("teachers/$potokId") { teachers[potokId].orEmpty() }

        private fun answer(where: String, result: () -> Set<Int>): Set<Int> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "ISU request inside a transaction")
            calls.add(where)
            beforeRequest(where)
            (failOnce.remove(where) ?: failAlways[where])?.let { throw IsuFailure(it, where) }
            return result()
        }

        fun reset() {
            teachers.clear()
            members.clear()
            loginFailure = null
            rotated = null
            rotatedExpiresAt = null
            failAlways.clear()
            failOnce.clear()
            beforeRequest = {}
            calls.clear()
            identities.clear()
        }
    }

    private var nextIsu = FIRST_ISU

    @BeforeEach
    fun reset() {
        cleanup()
        clock.now = NOW
        isu.reset()
        // Drops a session left in memory by the previous test; nothing is pending, so nothing is requested.
        service.onIdentityReplaced()
        isu.calls.clear()
    }

    @AfterEach
    fun cleanup() {
        jdbc.update("DELETE FROM lessons WHERE user_isu BETWEEN ? AND ?", FIRST_ISU, ADMIN_ISU)
        jdbc.update("DELETE FROM admin_audit WHERE actor_id IN (SELECT id FROM users WHERE isu = ?)", ADMIN_ISU)
        jdbc.update("DELETE FROM users WHERE isu BETWEEN ? AND ?", FIRST_ISU, ADMIN_ISU)
        jdbc.update("DELETE FROM isu_potoks")
        jdbc.update("""
            UPDATE service_credentials SET value = NULL, expires_at = NULL, status = 'MISSING', last_used_at = NULL,
                last_renewed_at = NULL, last_error_at = NULL, last_error = NULL, updated_by = NULL, updated_source = NULL,
                updated_at = now()
            """)
    }

    @Test
    fun `a flow with the teacher and the author verifies the review without member lists of other flows`() {
        cookie()
        val author = user()
        lesson(author, flowId = 777)
        val id = review(author, flows = listOf(FLOW))
        isu.teachers[777] = setOf(OTHER_TEACHER)
        isu.teachers[FLOW] = setOf(OTHER_TEACHER, TEACHER)
        isu.members[FLOW] = setOf(author.isu, author.isu + 1)

        service.kick()

        assertEquals(Row("VERIFIED", FLOW, null, 0, NOW), row(id))
        assertEquals(listOf("login", "teachers/777", "teachers/$FLOW", "members/$FLOW"), isu.calls)
        val state = credentials.state(ISU)
        assertEquals(ServiceCredentialStatus.OK, state.status)
        assertEquals(NOW, state.lastUsedAt)
    }

    @Test
    fun `candidates come from lessons then the saved flows then schedule flows without repeats and at most forty`() {
        val author = user()
        lesson(author, flowId = 11, date = LocalDate.of(2026, 9, 1))
        lesson(author, flowId = 10, date = LocalDate.of(2026, 9, 20))
        val id = review(author, flows = listOf(12, 11))
        subjectFlow(author, 13, LocalDate.of(2026, 9, 25))
        subjectFlow(author, 10, LocalDate.of(2026, 9, 24))
        (100L..144L).forEach { subjectFlow(author, it, LocalDate.of(2026, 9, 23).minusDays(it - 100)) }

        val task = store.due(NOW, 20).single { it.reviewId == id }

        val expected = listOf(10L, 11L, 12L, 13L) + (100L..135L)
        assertEquals(expected, store.candidates(task))
        assertEquals(40, store.candidates(task).size)
    }

    @Test
    fun `a review whose teacher never taught its author in any candidate flow is unverified`() {
        cookie()
        val author = user()
        val id = review(author, flows = listOf(FLOW, 96388))
        isu.teachers[FLOW] = setOf(TEACHER)
        isu.members[FLOW] = setOf(author.isu + 1)
        isu.teachers[96388] = setOf(OTHER_TEACHER)

        service.kick()

        assertEquals(Row("UNVERIFIED", null, null, 0, NOW), row(id))
        assertFalse("members/96388" in isu.calls)
    }

    @Test
    fun `members are cached for a day and teachers for thirty days`() {
        cookie()
        val first = user()
        val second = user()
        val third = user()
        isu.teachers[FLOW] = setOf(TEACHER)
        isu.members[FLOW] = setOf(first.isu, second.isu, third.isu)
        val a = review(first, flows = listOf(FLOW))
        service.kick()
        assertEquals(listOf("login", "teachers/$FLOW", "members/$FLOW"), isu.calls)

        isu.calls.clear()
        clock.now = NOW.plus(Duration.ofHours(23))
        val b = review(second, flows = listOf(FLOW), dueAt = clock.now)
        service.kick()
        assertEquals(emptyList(), isu.calls)

        clock.now = NOW.plus(Duration.ofHours(25))
        val c = review(third, flows = listOf(FLOW), dueAt = clock.now)
        service.kick()
        assertEquals(listOf("members/$FLOW"), isu.calls)
        assertEquals(listOf("VERIFIED", "VERIFIED", "VERIFIED"), listOf(a, b, c).map { row(it).verification })
    }

    @Test
    fun `without a cookie reviews wait six hours and ISU is not contacted`() {
        val id = review(user(), flows = listOf(FLOW))

        service.kick()

        assertEquals(Row("PENDING", null, NOW.plus(Duration.ofHours(6)), 0, null), row(id))
        assertEquals(emptyList(), isu.calls)
        assertEquals(ServiceCredentialStatus.MISSING, credentials.state(ISU).status)
    }

    @Test
    fun `an expired cookie holds reviews until a replacement queues them at once`() {
        cookie()
        val author = user()
        val id = review(author, flows = listOf(FLOW))
        isu.teachers[FLOW] = setOf(TEACHER)
        isu.members[FLOW] = setOf(author.isu)
        isu.loginFailure = IsuErrorCategory.EXPIRED

        service.kick()

        assertEquals(Row("PENDING", null, NOW.plus(Duration.ofHours(6)), 0, null), row(id))
        val expired = credentials.state(ISU)
        assertEquals(ServiceCredentialStatus.EXPIRED, expired.status)
        assertEquals("EXPIRED login", expired.lastError)
        assertEquals(NOW, expired.lastErrorAt)
        review(user(), flows = listOf(FLOW))
        service.kick()
        assertEquals(listOf("login"), isu.calls, "A known expired cookie is not tried again")

        isu.loginFailure = null
        credentials.replace(ISU, "synthetic-replaced-cookie", admin())

        assertEquals(Row("VERIFIED", FLOW, null, 0, NOW), row(id))
        assertEquals(listOf("synthetic-cookie", "synthetic-replaced-cookie"), isu.identities)
        assertEquals(ServiceCredentialStatus.OK, credentials.state(ISU).status)
    }

    @Test
    fun `a seeded or replaced cookie is checked by a login even without reviews`() {
        credentials.initializeFromBootstrap(ISU, "synthetic-seed-cookie")
        assertEquals(ServiceCredentialStatus.UNKNOWN, credentials.state(ISU).status)

        service.kick()

        assertEquals(listOf("login"), isu.calls)
        val state = credentials.state(ISU)
        assertEquals(ServiceCredentialStatus.OK, state.status)
        assertEquals(NOW, state.lastRenewedAt)
        service.kick()
        assertEquals(listOf("login"), isu.calls)

        clock.now = NOW.plusSeconds(60)
        credentials.replace(ISU, "synthetic-replaced-cookie", admin())
        assertEquals(listOf("login", "login"), isu.calls)
        assertEquals(NOW.plusSeconds(60), credentials.state(ISU).lastRenewedAt)
    }

    @Test
    fun `a network failure postpones the review with a growing backoff capped at a day`() {
        cookie()
        val author = user()
        val id = review(author, flows = listOf(FLOW))
        isu.teachers[FLOW] = setOf(TEACHER)
        isu.failAlways["members/$FLOW"] = IsuErrorCategory.NETWORK

        service.kick()

        assertEquals(Row("PENDING", null, NOW.plus(Duration.ofMinutes(30)), 1, null), row(id))
        val failed = credentials.state(ISU)
        assertEquals(ServiceCredentialStatus.FAILED, failed.status)
        assertEquals("NETWORK members/$FLOW", failed.lastError)

        clock.now = NOW.plus(Duration.ofMinutes(30))
        service.kick()
        assertEquals(Row("PENDING", null, clock.now.plus(Duration.ofHours(1)), 2, null), row(id))

        jdbc.update("UPDATE teacher_reviews SET verification_attempts = 10, verification_due_at = ? WHERE id = ?", ts(clock.now), id)
        service.kick()
        assertEquals(Row("PENDING", null, clock.now.plus(Duration.ofHours(24)), 11, null), row(id))
    }

    @Test
    fun `a mapping failure postpones one review and the run goes on`() {
        cookie()
        val broken = review(user(), flows = listOf(96388))
        val author = user()
        val next = review(author, flows = listOf(FLOW), dueAt = NOW.plusMillis(1))
        clock.now = NOW.plusMillis(1)
        isu.failAlways["teachers/96388"] = IsuErrorCategory.MAPPING
        isu.teachers[FLOW] = setOf(TEACHER)
        isu.members[FLOW] = setOf(author.isu)

        service.kick()

        assertEquals(Row("PENDING", null, clock.now.plus(Duration.ofMinutes(30)), 1, null), row(broken))
        assertEquals("VERIFIED", row(next).verification)
        assertEquals(ServiceCredentialStatus.OK, credentials.state(ISU).status, "A later successful request restores the status")
    }

    @Test
    fun `a lost session gets one new login and the request is repeated`() {
        cookie()
        val author = user()
        val id = review(author, flows = listOf(FLOW))
        isu.teachers[FLOW] = setOf(TEACHER)
        isu.members[FLOW] = setOf(author.isu)
        isu.failOnce["teachers/$FLOW"] = IsuErrorCategory.SESSION_LOST

        service.kick()

        assertEquals(listOf("login", "teachers/$FLOW", "login", "teachers/$FLOW", "members/$FLOW"), isu.calls)
        assertEquals("VERIFIED", row(id).verification)

        val lost = review(user(), flows = listOf(96388))
        isu.failAlways["teachers/96388"] = IsuErrorCategory.SESSION_LOST
        isu.calls.clear()
        service.kick()
        assertEquals(listOf("teachers/96388", "login", "teachers/96388"), isu.calls)
        assertEquals(Row("PENDING", null, NOW.plus(Duration.ofMinutes(30)), 1, null), row(lost))
        assertEquals("SESSION_LOST teachers/96388", credentials.state(ISU).lastError)
    }

    @Test
    fun `a rotated cookie from the login is stored and successful requests mark the cookie used`() {
        cookie()
        val author = user()
        review(author, flows = listOf(FLOW))
        isu.teachers[FLOW] = setOf(TEACHER)
        isu.members[FLOW] = setOf(author.isu)
        isu.rotated = "synthetic-rotated-cookie"
        isu.rotatedExpiresAt = NOW.plus(Duration.ofDays(90))

        service.kick()

        assertEquals("synthetic-rotated-cookie", credentials.value(ISU))
        val state = credentials.state(ISU)
        assertEquals(CredentialSource.ROTATION, state.updatedSource)
        assertEquals(NOW.plus(Duration.ofDays(90)), state.expiresAt)
        assertEquals(ServiceCredentialStatus.OK, state.status)
        assertEquals(NOW, state.lastUsedAt)
        assertEquals(NOW, state.lastRenewedAt)
    }

    @Test
    fun `replacing the MyITMO refresh token keeps the ISU session and the queue`() {
        cookie()
        val author = user()
        review(author, flows = listOf(FLOW))
        isu.teachers[FLOW] = setOf(TEACHER)
        isu.members[FLOW] = setOf(author.isu)
        service.kick()
        val later = review(user(), flows = listOf(FLOW), dueAt = NOW.plus(Duration.ofHours(1)))

        credentials.replace(ServiceCredential.MY_ITMO_REFRESH_TOKEN, "synthetic-refresh-token-value", admin())

        assertEquals(NOW.plus(Duration.ofHours(1)), row(later).dueAt)
        review(user(), flows = listOf(FLOW))
        service.kick()
        assertEquals(1, isu.calls.count { it == "login" })
    }

    @Test
    fun `a save by the author during the check is not overwritten`() {
        cookie()
        val author = user()
        val id = review(author, flows = listOf(FLOW))
        isu.teachers[FLOW] = setOf(TEACHER)
        isu.members[FLOW] = setOf(author.isu)
        isu.beforeRequest = { where ->
            if (where == "members/$FLOW") {
                jdbc.update("UPDATE teacher_reviews SET verification_due_at = ? WHERE id = ?", ts(NOW.plusSeconds(1)), id)
            }
        }

        service.kick()

        assertEquals(Row("PENDING", null, NOW.plusSeconds(1), 0, null), row(id))
    }

    @Test
    fun `a review deleted during the check is skipped without errors`() {
        cookie()
        val author = user()
        val id = review(author, flows = listOf(FLOW), dueAt = NOW.minusSeconds(1))
        val other = user()
        val next = review(other, flows = listOf(FLOW))
        isu.teachers[FLOW] = setOf(TEACHER)
        isu.members[FLOW] = setOf(author.isu, other.isu)
        isu.beforeRequest = { where -> if (where == "members/$FLOW") jdbc.update("DELETE FROM teacher_reviews WHERE id = ?", id) }

        service.kick()

        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM teacher_reviews WHERE id = ?", Long::class.javaObjectType, id))
        assertEquals("VERIFIED", row(next).verification)
    }

    @Test
    fun `maintenance purges stale lists and logs in only after a week without renewal`() {
        cache.saveTeachers(1, setOf(TEACHER), NOW.minus(Duration.ofDays(2)))
        cache.saveMembers(1, setOf(FIRST_ISU), NOW.minus(Duration.ofDays(2)))
        cache.saveTeachers(2, setOf(TEACHER), NOW.minus(Duration.ofDays(31)))
        cache.saveTeachers(3, setOf(TEACHER), NOW.minus(Duration.ofDays(31)))
        cache.saveMembers(3, setOf(FIRST_ISU), NOW.minus(Duration.ofHours(1)))
        cookie(renewedAt = NOW.minus(Duration.ofDays(8)))

        service.maintenance()

        assertEquals(listOf(1L, 3L), jdbc.queryForList("SELECT potok_id FROM isu_potoks ORDER BY potok_id", Long::class.javaObjectType))
        assertNull(cache.isMember(1, FIRST_ISU, NOW))
        assertEquals(setOf(TEACHER), cache.teachers(1, NOW))
        assertEquals(true, cache.isMember(3, FIRST_ISU, NOW))
        assertEquals(listOf("login"), isu.calls)
        assertEquals(NOW, credentials.state(ISU).lastRenewedAt)

        cookie(renewedAt = NOW.minus(Duration.ofDays(1)))
        service.maintenance()
        cookie(status = "EXPIRED", renewedAt = NOW.minus(Duration.ofDays(30)))
        service.maintenance()
        assertEquals(listOf("login"), isu.calls)
    }

    private data class Row(val verification: String, val flow: Long?, val dueAt: Instant?, val attempts: Int, val checkedAt: Instant?)

    private fun row(id: UUID): Row = jdbc.queryForObject(
        """
        SELECT verification, verified_flow_id, verification_due_at, verification_attempts, verification_checked_at
        FROM teacher_reviews WHERE id = ?
        """,
        { rs, _ ->
            Row(rs.getString(1), rs.getObject(2) as Long?, rs.getObject(3, OffsetDateTime::class.java)?.toInstant(), rs.getInt(4),
                rs.getObject(5, OffsetDateTime::class.java)?.toInstant())
        },
        id,
    )!!

    private data class Author(val id: UUID, val isu: Int)

    private fun user(isu: Int = nextIsu++): Author {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, isu, name) VALUES (?, ?, 'Synthetic user')", id, isu)
        jdbc.update("INSERT INTO user_settings (user_id) VALUES (?)", id)
        return Author(id, isu)
    }

    private fun admin(): UUID = user(ADMIN_ISU).id

    private fun review(author: Author, flows: List<Long> = emptyList(), dueAt: Instant = NOW): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO teacher_reviews (id, author_id, teacher_isu, text, verification, verification_due_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?)
            """,
            id, author.id, TEACHER, TEXT, ts(dueAt), ts(NOW), ts(NOW),
        )
        flows.forEach { jdbc.update("INSERT INTO teacher_review_flows (review_id, flow_id) VALUES (?, ?)", id, it) }
        return id
    }

    private fun lesson(author: Author, flowId: Long, date: LocalDate = LocalDate.of(2026, 9, 21)) = jdbc.update(
        """
        INSERT INTO lessons (id, user_isu, date, pair_id, subject_id, subject_name, teacher_isu, start_time, end_time, type,
            type_id, group_name, flow_id, flow_type_id, format, format_id)
        VALUES (?, ?, ?, ?, 1, 'Synthetic subject', ?, '10:00', '11:30', 'Лекция', 1, 'M3100', ?, 2, 'Очно', 1)
        """,
        UUID.randomUUID(), author.isu, date, flowId * 1000 + date.dayOfYear, TEACHER.toLong(), flowId,
    )

    private fun subjectFlow(author: Author, flowId: Long, lastSeen: LocalDate) = jdbc.update(
        """
        INSERT INTO user_subject_flows (user_id, subject_id, period_key, flow_id, group_name, type_id, last_seen)
        VALUES (?, ?, '2026-1', ?, 'M3100', 2, ?)
        """,
        author.id, flowId, flowId, lastSeen,
    )

    private fun cookie(status: String = "OK", renewedAt: Instant? = null) = jdbc.update(
        """
        UPDATE service_credentials SET value = 'synthetic-cookie', status = ?, updated_source = 'SEED', last_renewed_at = ?,
            updated_at = now()
        WHERE key = 'ISU_KEYCLOAK_IDENTITY'
        """,
        status, renewedAt?.let(::ts),
    )

    private fun ts(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    private companion object {
        val ISU = ServiceCredential.ISU_KEYCLOAK_IDENTITY
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
        const val FIRST_ISU = 966100
        const val ADMIN_ISU = 966199
        const val TEACHER = 142415
        const val OTHER_TEACHER = 471029
        const val FLOW = 93724L
        const val TEXT = "Синтетический отзыв о преподавателе для теста"
    }
}
