package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminUserSummaries
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalReviewSyncStateEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewProvider
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewSyncOutcome
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalReviewSyncStateRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalTeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebLoginServiceTest
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
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

/** The store commits on its own, so this class runs without a test transaction and cleans up after itself. */
@Import(ReviewsSyncService::class, ReviewsSyncStore::class, AdminAuditService::class, AdminAccess::class, AdminUserSummaries::class,
    ReviewsSyncServiceTest.TestConfig::class)
// A @Bean of a @ConfigurationProperties class would be rebound, so the test binds its values instead.
@TestPropertySource(properties = ["itmowidgets.reviews-sync.enabled=true", "itmowidgets.reviews-sync.request-delay=0ms"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewsSyncServiceTest @Autowired constructor(
    private val service: ReviewsSyncService,
    private val store: ReviewsSyncStore,
    private val api: FakeReviewsApi,
    private val clock: WebLoginServiceTest.MutableClock,
    private val reviews: ExternalTeacherReviewRepository,
    private val states: ExternalReviewSyncStateRepository,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReviewsSyncConfig::class)
    class TestConfig {
        @Bean fun fakeReviewsApi() = FakeReviewsApi()
        @Bean fun reviewsSyncExecutor(): TaskExecutor = SyncTaskExecutor()
        @Bean fun clock() = WebLoginServiceTest.MutableClock()
    }

    /** An in-memory Reviews project: registry ids are the teachers' keys unless [registryIds] overrides them. */
    class FakeReviewsApi : ReviewsApiClient {
        var etag: String? = "\"v1\""
        var teachers: Map<Long, ReviewsTeacher> = emptyMap()
        var registryIds: List<Long>? = null
        /** The N-th teacher request of a run answers HTTP 503. */
        var failOnTeacher: Int? = null
        val calls = mutableListOf<String>()
        private var teacherRequests = 0

        override fun registry(etag: String?): RegistryResult {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Reviews request inside a transaction")
            calls.add("/registry")
            teacherRequests = 0
            if (etag != null && etag == this.etag) return RegistryResult.NotModified
            val ids = registryIds ?: teachers.keys.sorted()
            if (ids.isEmpty()) throw ReviewsSyncFailure(ReviewSyncErrorCategory.MAPPING, "/registry empty")
            return RegistryResult.Changed(this.etag, ids)
        }

        override fun teacher(id: Long): ReviewsTeacher? {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Reviews request inside a transaction")
            calls.add("/teacher/$id")
            if (++teacherRequests == failOnTeacher) throw ReviewsSyncFailure(ReviewSyncErrorCategory.HTTP, "/teacher/$id", 503)
            return teachers[id]
        }

        fun reset() {
            etag = "\"v1\""
            teachers = emptyMap()
            registryIds = null
            failOnTeacher = null
            calls.clear()
        }
    }

    private lateinit var adminId: UUID

    @BeforeEach
    fun fixture() {
        cleanUp()
        api.reset()
        clock.now = NOW
        adminId = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, isu, name) VALUES (?, ?, 'Synthetic admin')", adminId, ADMIN_ISU)
    }

    @AfterEach
    fun cleanUp() {
        jdbc.update("DELETE FROM external_teacher_reviews")
        jdbc.update("""
            UPDATE external_review_sync_state SET etag = NULL, running_since = NULL, last_checked_at = NULL, last_changed_at = NULL,
                last_success_at = NULL, last_outcome = NULL, last_error = NULL, last_added = 0, last_updated = 0, last_removed = 0,
                teachers_total = 0, reviews_total = 0
        """)
        jdbc.update("DELETE FROM admin_audit WHERE actor_id IN (SELECT id FROM users WHERE isu = ?)", ADMIN_ISU)
        jdbc.update("DELETE FROM users WHERE isu = ?", ADMIN_ISU)
    }

    @Test
    fun `the first run inserts every review with its teacher dates ETag and counters`() {
        api.teachers = mapOf(
            100001L to teacher(100001, comment(1, "Первый", "12:18 25.01.2025"), comment(2, "Второй", "до 2024")),
            100002L to teacher(100002, comment(3, "Третий", "")),
        )

        service.startManual(adminId)

        assertEquals(listOf("/registry", "/teacher/100001", "/teacher/100002"), api.calls)
        val rows = rows()
        assertEquals(setOf(1L, 2L, 3L), rows.keys)
        with(rows.getValue(1)) {
            assertEquals(100001, teacherIsu)
            assertEquals("Преподаватель 100001", teacherName)
            assertEquals("Первый", text)
            assertEquals("Математика", subjectTitle)
            assertEquals("Канал", sourceTitle)
            assertEquals("https://t.me/example/1", sourceLink)
            assertEquals(LocalDate.of(2025, 1, 25), writtenOn)
            assertNull(writtenBeforeYear)
            assertEquals(NOW, firstSeenAt)
            assertEquals(NOW, lastSeenAt)
            assertNull(removedAt)
        }
        assertEquals(2024, rows.getValue(2).writtenBeforeYear)
        assertNull(rows.getValue(2).writtenOn)
        with(rows.getValue(3)) {
            assertEquals(100002, teacherIsu)
            assertEquals("", dateRaw)
            assertNull(writtenOn)
            assertNull(writtenBeforeYear)
        }
        with(state()) {
            assertEquals("\"v1\"", etag)
            assertEquals(ReviewSyncOutcome.UPDATED, lastOutcome)
            assertNull(lastError)
            assertNull(runningSince)
            assertEquals(listOf(NOW, NOW, NOW), listOf(lastCheckedAt, lastChangedAt, lastSuccessAt))
            assertEquals(listOf(3, 0, 0), listOf(lastAdded, lastUpdated, lastRemoved))
            assertEquals(2, teachersTotal)
            assertEquals(3, reviewsTotal)
        }
    }

    @Test
    fun `a not modified registry fetches no teachers and only stamps the check`() {
        api.teachers = mapOf(100001L to teacher(100001, comment(1, "Первый"), comment(2, "Второй")))
        service.scheduledRun()
        api.calls.clear()
        val later = NOW.plus(Duration.ofDays(1))
        clock.now = later

        service.scheduledRun()

        assertEquals(listOf("/registry"), api.calls)
        with(state()) {
            assertEquals(ReviewSyncOutcome.UNCHANGED, lastOutcome)
            assertEquals(later, lastCheckedAt)
            assertEquals(later, lastSuccessAt)
            assertEquals(NOW, lastChangedAt)
            assertEquals("\"v1\"", etag)
            assertEquals(listOf(2, 0, 0), listOf(lastAdded, lastUpdated, lastRemoved))
            assertEquals(2, reviewsTotal)
            assertNull(runningSince)
        }
        for (row in rows().values) {
            assertEquals(NOW, row.firstSeenAt)
            assertEquals(NOW, row.lastSeenAt)
        }
    }

    @Test
    fun `changed text is updated in place missing reviews are removed and returning ones restored`() {
        api.teachers = mapOf(
            100001L to teacher(100001, comment(1, "Первый"), comment(2, "Второй")),
            100002L to teacher(100002, comment(3, "Третий")),
        )
        service.scheduledRun()

        val second = NOW.plus(Duration.ofHours(1))
        clock.now = second
        api.etag = "\"v2\""
        api.teachers = mapOf(
            100001L to teacher(100001, comment(1, "Первый, исправленный"), comment(2, "Второй")),
            100002L to teacher(100002),
        )
        service.scheduledRun()

        var rows = rows()
        assertEquals("Первый, исправленный", rows.getValue(1).text)
        assertEquals(NOW, rows.getValue(1).firstSeenAt)
        assertEquals(second, rows.getValue(1).lastSeenAt)
        assertEquals(second, rows.getValue(2).lastSeenAt)
        assertEquals(second, rows.getValue(3).removedAt)
        assertEquals(NOW, rows.getValue(3).lastSeenAt)
        with(state()) {
            assertEquals(listOf(0, 1, 1), listOf(lastAdded, lastUpdated, lastRemoved))
            assertEquals("\"v2\"", etag)
            assertEquals(2, reviewsTotal)
        }

        val third = second.plus(Duration.ofHours(1))
        clock.now = third
        api.etag = "\"v3\""
        api.teachers = mapOf(
            100001L to teacher(100001, comment(1, "Первый, исправленный"), comment(2, "Второй")),
            100002L to teacher(100002, comment(3, "Третий")),
            100003L to teacher(100003, comment(4, "Четвёртый")),
        )
        service.scheduledRun()

        rows = rows()
        assertNull(rows.getValue(3).removedAt)
        assertEquals(third, rows.getValue(3).lastSeenAt)
        assertEquals(NOW, rows.getValue(3).firstSeenAt)
        assertEquals(third, rows.getValue(4).firstSeenAt)
        with(state()) {
            assertEquals(listOf(1, 1, 0), listOf(lastAdded, lastUpdated, lastRemoved))
            assertEquals(3, teachersTotal)
            assertEquals(4, reviewsTotal)
        }
    }

    @Test
    fun `an unknown teacher has its reviews removed and the run completes`() {
        api.teachers = mapOf(
            100001L to teacher(100001, comment(1, "Первый")),
            100002L to teacher(100002, comment(2, "Второй"), comment(3, "Третий")),
        )
        service.scheduledRun()
        clock.now = NOW.plusSeconds(60)
        api.etag = "\"v2\""
        api.registryIds = listOf(100001, 100002)
        api.teachers = mapOf(100001L to teacher(100001, comment(1, "Первый")))

        service.scheduledRun()

        val rows = rows()
        assertNull(rows.getValue(1).removedAt)
        assertEquals(NOW.plusSeconds(60), rows.getValue(2).removedAt)
        assertEquals(NOW.plusSeconds(60), rows.getValue(3).removedAt)
        with(state()) {
            assertEquals(ReviewSyncOutcome.UPDATED, lastOutcome)
            assertEquals(listOf(0, 0, 2), listOf(lastAdded, lastUpdated, lastRemoved))
            assertEquals(1, teachersTotal)
            assertEquals(1, reviewsTotal)
            assertEquals("\"v2\"", etag)
        }
    }

    @Test
    fun `a failing teacher changes no row keeps the ETag and the next run applies the snapshot`() {
        api.teachers = mapOf(
            100001L to teacher(100001, comment(1, "Первый")),
            100002L to teacher(100002, comment(2, "Второй")),
            100003L to teacher(100003, comment(3, "Третий")),
        )
        service.scheduledRun()
        val failedAt = NOW.plusSeconds(60)
        clock.now = failedAt
        api.etag = "\"v2\""
        api.teachers = mapOf(
            100001L to teacher(100001, comment(1, "Первый, исправленный")),
            100002L to teacher(100002),
            100003L to teacher(100003, comment(3, "Третий")),
        )
        api.failOnTeacher = 3

        service.scheduledRun()

        val rows = rows()
        assertEquals("Первый", rows.getValue(1).text)
        assertTrue(rows.values.all { it.removedAt == null && it.lastSeenAt == NOW })
        with(state()) {
            assertEquals(ReviewSyncOutcome.FAILED, lastOutcome)
            assertEquals("HTTP 503 /teacher/100003", lastError)
            assertEquals("\"v1\"", etag)
            assertEquals(failedAt, lastCheckedAt)
            assertEquals(NOW, lastSuccessAt)
            assertEquals(NOW, lastChangedAt)
            assertEquals(3, reviewsTotal)
            assertNull(runningSince)
        }

        api.failOnTeacher = null
        api.calls.clear()
        service.scheduledRun()

        assertEquals(listOf("/registry", "/teacher/100001", "/teacher/100002", "/teacher/100003"), api.calls)
        assertEquals("Первый, исправленный", rows().getValue(1).text)
        assertEquals(failedAt, rows().getValue(2).removedAt)
        with(state()) {
            assertEquals(ReviewSyncOutcome.UPDATED, lastOutcome)
            assertNull(lastError)
            assertEquals("\"v2\"", etag)
            assertEquals(listOf(0, 1, 1), listOf(lastAdded, lastUpdated, lastRemoved))
        }
    }

    @Test
    fun `an empty registry fails and removes nothing`() {
        api.teachers = mapOf(100001L to teacher(100001, comment(1, "Первый")))
        service.scheduledRun()
        api.etag = "\"v2\""
        api.teachers = emptyMap()

        service.scheduledRun()

        assertNull(rows().getValue(1).removedAt)
        with(state()) {
            assertEquals(ReviewSyncOutcome.FAILED, lastOutcome)
            assertEquals("MAPPING /registry empty", lastError)
            assertEquals("\"v1\"", etag)
        }
    }

    @Test
    fun `a held lease rejects a manual start and a scheduled run until it goes stale`() {
        api.teachers = mapOf(100001L to teacher(100001, comment(1, "Первый")))
        assertTrue(store.claim(NOW))

        assertEquals("Reviews sync is already running", assertFailsWith<BusinessRuleException> { service.startManual(adminId) }.message)
        service.scheduledRun()
        assertTrue(api.calls.isEmpty())
        assertEquals(0, auditRows())
        assertEquals(NOW, state().runningSince)

        clock.now = NOW.plus(Duration.ofHours(7))
        service.scheduledRun()

        assertEquals(listOf("/registry", "/teacher/100001"), api.calls)
        assertEquals(ReviewSyncOutcome.UPDATED, state().lastOutcome)
        assertNull(state().runningSince)
    }

    @Test
    fun `a disabled sync neither runs on schedule nor starts manually`() {
        val disabled = ReviewsSyncService(api, store, ReviewsSyncConfig(enabled = false), SyncTaskExecutor(), clock)

        disabled.scheduledRun()
        assertEquals("Reviews sync is disabled", assertFailsWith<BusinessRuleException> { disabled.startManual(adminId) }.message)

        assertTrue(api.calls.isEmpty())
        assertEquals(0, auditRows())
        assertNull(state().lastOutcome)
        assertNull(state().runningSince)
    }

    @Test
    fun `a manual start is audited for the admin`() {
        api.teachers = mapOf(100001L to teacher(100001, comment(1, "Первый")))

        service.startManual(adminId)

        assertEquals(mapOf<String, Any?>("action" to "REVIEWS_SYNC_STARTED", "target" to "reviews-sync", "details" to null),
            jdbc.queryForMap("SELECT action, target, details FROM admin_audit WHERE actor_id = ?", adminId))
    }

    @Test
    fun `the ready event releases a leftover lease`() {
        assertTrue(store.claim(NOW))

        service.onApplicationEvent(mock(ApplicationReadyEvent::class.java))

        assertNull(state().runningSince)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a review listed under two teachers keeps the first one`() {
        api.teachers = mapOf(
            100001L to teacher(100001, comment(1, "Первый")),
            100002L to teacher(100002, comment(1, "Повтор"), comment(2, "Второй")),
        )

        service.scheduledRun()

        val rows = rows()
        assertEquals(100001, rows.getValue(1).teacherIsu)
        assertEquals("Первый", rows.getValue(1).text)
        assertEquals(100002, rows.getValue(2).teacherIsu)
        with(state()) {
            assertEquals(listOf(2, 0, 0), listOf(lastAdded, lastUpdated, lastRemoved))
            assertEquals(2, reviewsTotal)
        }
    }

    private fun rows(): Map<Long, ExternalTeacherReviewEntity> =
        reviews.findAllByProvider(ReviewProvider.REVIEWS_WORK_GD).associateBy { it.externalId }

    private fun state(): ExternalReviewSyncStateEntity = states.findById(ReviewProvider.REVIEWS_WORK_GD).orElseThrow()

    private fun auditRows(): Int =
        jdbc.queryForObject("SELECT count(*) FROM admin_audit WHERE actor_id = ?", Int::class.java, adminId)!!

    private fun teacher(id: Long, vararg comments: ReviewsComment) = ReviewsTeacher(id, "Преподаватель $id", comments.toList())

    private fun comment(id: Long, text: String, date: String = "12:18 25.01.2025") =
        ReviewsComment(id, date, text, "Математика", "Канал", "https://t.me/example/$id")

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T02:00:00Z")
        const val ADMIN_ISU = 962001
    }
}
