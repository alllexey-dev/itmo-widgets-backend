package dev.alllexey.itmowidgets.backend.repositories

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.alllexey.itmowidgets.backend.configs.AiSummaryConfig
import dev.alllexey.itmowidgets.backend.dto.TeacherReviewKind
import dev.alllexey.itmowidgets.backend.model.StoredSummary
import dev.alllexey.itmowidgets.backend.model.SummaryConfidence
import dev.alllexey.itmowidgets.backend.model.SummaryLevel
import dev.alllexey.itmowidgets.backend.model.SummaryRunTrigger
import dev.alllexey.itmowidgets.backend.services.AdminAccess
import dev.alllexey.itmowidgets.backend.services.AdminAuditService
import dev.alllexey.itmowidgets.backend.services.AdminUserSummaries
import dev.alllexey.itmowidgets.backend.services.SummaryInputReview
import dev.alllexey.itmowidgets.backend.services.SummaryVerdict
import dev.alllexey.itmowidgets.backend.services.TeacherSummaryInput
import dev.alllexey.itmowidgets.backend.services.TeacherSummaryStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Limit
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/** The store commits on its own, so this class runs without a test transaction and cleans up after itself. */
@Import(TeacherSummaryStore::class, AdminAuditService::class, AdminAccess::class, AdminUserSummaries::class,
    TeacherSummaryPersistenceTest.TestConfig::class)
@TestPropertySource(properties = ["itmowidgets.ai-summary.daily-request-budget=3"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeacherSummaryPersistenceTest @Autowired constructor(
    private val store: TeacherSummaryStore,
    private val summaries: TeacherSummaryRepository,
    private val jdbc: JdbcTemplate,
    private val manager: PlatformTransactionManager,
) : PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSummaryConfig::class)
    class TestConfig {
        @Bean fun objectMapper() = jacksonObjectMapper()

        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    private lateinit var adminId: UUID

    @BeforeEach
    fun fixture() {
        cleanUp()
        adminId = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, isu, name) VALUES (?, ?, 'Synthetic admin')", adminId, ADMIN_ISU)
    }

    @AfterEach
    fun cleanUp() {
        jdbc.update("DELETE FROM teacher_summaries")
        jdbc.update("""
            UPDATE teacher_summary_state SET running_since = NULL, last_started_at = NULL, last_finished_at = NULL,
                last_trigger = NULL, last_outcome = NULL, last_error = NULL, last_generated = 0, last_failed = 0,
                last_requests = 0, budget_day = NULL, budget_used = 0
        """)
        jdbc.update("DELETE FROM admin_audit WHERE actor_id IN (SELECT id FROM users WHERE isu = ?)", ADMIN_ISU)
        jdbc.update("DELETE FROM users WHERE isu = ?", ADMIN_ISU)
    }

    @Test
    fun `plan creates eligible rows, restarts attempts on a new input and clears teachers that became ineligible`() {
        store.plan(mapOf(A to input(A, 3, "a1"), B to input(B, 4, "b1"), C to input(C, 5, "c1")), NOW)
        assertEquals(listOf(Row(A, hash("a1"), 3), Row(B, hash("b1"), 4), Row(C, hash("c1"), 5)), rows())

        record(B, input(B, 4, "b1"))
        record(C, input(C, 5, "c1"))
        tx { summaries.setHidden(C, NOW, adminId, NOW) }
        tx { summaries.recordRejected(A, "SCHEMA scales", NOW) }
        tx { summaries.recordRejected(B, "SCHEMA scales", NOW) }
        store.plan(mapOf(A to input(A, 3, "a1"), B to input(B, 6, "b2")), NOW.plusSeconds(60))

        assertEquals(listOf(Row(A, hash("a1"), 3), Row(B, hash("b2"), 6), Row(C, null, 0)), rows())
        assertEquals(1, attempts(A))
        assertEquals(0, attempts(B))
        assertEquals(hash("b1"), contentHash(B))
        // An ineligible teacher loses the content at once but stays hidden.
        assertNull(contentHash(C))
        assertEquals(1, count("teacher_isu = $C AND hidden_at IS NOT NULL AND hidden_by = '$adminId'"))

        store.planTeacher(C, input(C, 3, "c2"), NOW)
        store.planTeacher(D, input(D, 3, "d1"), NOW)
        store.planTeacher(A, null, NOW)
        assertEquals(listOf(Row(A, null, 0), Row(B, hash("b2"), 6), Row(C, hash("c2"), 3), Row(D, hash("d1"), 3)), rows())
    }

    @Test
    fun `the queue takes admin requests first, then the most reviewed, once per run`() {
        store.plan(mapOf(A to input(A, 3, "a"), B to input(B, 7, "b"), C to input(C, 5, "c"), D to input(D, 9, "d"),
            E to input(E, 8, "e"), F to input(F, 6, "f")), NOW)
        record(D, input(D, 9, "d"))
        tx { summaries.setHidden(E, NOW, adminId, NOW) }
        jdbc.update("UPDATE teacher_summaries SET attempts = 3 WHERE teacher_isu = ?", F)
        tx { summaries.request(A, NOW) }
        val runStartedAt = NOW.plusSeconds(60)

        assertEquals(listOf(A, B, C), queue(runStartedAt))

        tx { summaries.markAttempt(A, runStartedAt) }
        tx { summaries.markAttempt(B, runStartedAt.plusSeconds(1)) }
        assertEquals(listOf(C), queue(runStartedAt))
        // A new admin request after the attempt makes the teacher available in the same run.
        tx { summaries.request(B, runStartedAt.plusSeconds(2)) }
        assertEquals(listOf(B, C), queue(runStartedAt))
        // A later run tries everybody again.
        assertEquals(listOf(A, B, C), queue(runStartedAt.plusSeconds(3600)))
        // A requested READY summary is rebuilt.
        tx { summaries.request(D, runStartedAt.plusSeconds(3)) }
        assertEquals(listOf(B, D, C), queue(runStartedAt))
    }

    @Test
    fun `the day budget counts up to the limit and starts over on a new Pacific day`() {
        // 06:59 UTC is still the previous day in Los Angeles.
        val lateEvening = Instant.parse("2026-09-30T06:59:00Z")
        assertEquals(listOf(true, true, true, false), (1..4).map { store.takeBudget(lateEvening) })
        assertEquals(LocalDate.of(2026, 9, 29) to 3, budget())

        assertTrue(store.takeBudget(Instant.parse("2026-09-30T07:01:00Z")))
        assertEquals(LocalDate.of(2026, 9, 30) to 1, budget())
    }

    @Test
    fun `two parallel takes of the last unit get exactly one`() {
        jdbc.update("UPDATE teacher_summary_state SET budget_day = ?, budget_used = 2", LocalDate.of(2026, 9, 29))
        assertEquals(listOf(false, true), parallel { store.takeBudget(NOW) }.sorted())
        assertEquals(LocalDate.of(2026, 9, 29) to 3, budget())
    }

    @Test
    fun `hiding and recording content never overwrite each other`() {
        store.plan(mapOf(A to input(A, 3, "a"), B to input(B, 3, "b")), NOW)
        tx { summaries.setHidden(A, NOW, adminId, NOW) }
        assertTrue(record(A, input(A, 3, "a")))
        assertEquals(1, count("teacher_isu = $A AND hidden_at IS NOT NULL AND content IS NOT NULL AND content_hash = input_hash"))

        assertTrue(record(B, input(B, 3, "b")))
        tx { summaries.setHidden(B, NOW, adminId, NOW) }
        assertEquals(1, count("teacher_isu = $B AND hidden_at IS NOT NULL AND content IS NOT NULL AND attempts = 0 AND level = 'MIXED'"))

        // Content never lands on a row without input.
        store.planTeacher(B, null, NOW)
        assertFalse(record(B, input(B, 3, "b")))
        assertEquals(1, count("teacher_isu = $B AND content IS NULL"))
    }

    @Test
    fun `exactly one of two parallel claims wins and a stale lease is taken`() {
        assertEquals(listOf(false, true), parallel { store.claim(SummaryRunTrigger.SCHEDULE, NOW) }.sorted())
        assertFalse(store.claim(SummaryRunTrigger.SCHEDULE, NOW.plus(Duration.ofHours(5))))
        assertFalse(store.claimManual(adminId, NOW.plus(Duration.ofHours(5))))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM admin_audit WHERE actor_id = ?", Int::class.java, adminId))

        assertTrue(store.claimManual(adminId, NOW.plus(Duration.ofHours(6)).plusSeconds(1)))
        assertEquals(SummaryRunTrigger.ADMIN, store.state().lastTrigger)
        assertEquals(listOf("AI_SUMMARIES_RUN_STARTED ai-summaries"), jdbc.queryForList(
            "SELECT action || ' ' || target FROM admin_audit WHERE actor_id = ?", String::class.java, adminId))
        store.release()
        assertNull(store.state().runningSince)
    }

    private fun tx(action: () -> Unit) = TransactionTemplate(manager).executeWithoutResult { action() }

    private data class Row(val isu: Int, val hash: String?, val count: Int)

    private fun rows(): List<Row> = jdbc.query("SELECT teacher_isu, input_hash, input_count FROM teacher_summaries ORDER BY teacher_isu") { rs, _ ->
        Row(rs.getInt(1), rs.getString(2), rs.getInt(3))
    }

    private fun queue(runStartedAt: Instant): List<Int> =
        summaries.findNext(3, runStartedAt, Limit.of(10)).map { it.teacherIsu }

    private fun attempts(isu: Int): Int =
        jdbc.queryForObject("SELECT attempts FROM teacher_summaries WHERE teacher_isu = ?", Int::class.java, isu)!!

    private fun contentHash(isu: Int): String? =
        jdbc.queryForObject("SELECT content_hash FROM teacher_summaries WHERE teacher_isu = ?", String::class.java, isu)

    private fun count(where: String): Int = jdbc.queryForObject("SELECT count(*) FROM teacher_summaries WHERE $where", Int::class.java)!!

    private fun budget(): Pair<LocalDate?, Int> = jdbc.queryForObject("SELECT budget_day, budget_used FROM teacher_summary_state") { rs, _ ->
        rs.getObject(1, LocalDate::class.java) to rs.getInt(2)
    }!!

    private fun record(isu: Int, input: TeacherSummaryInput): Boolean = store.recordSuccess(isu, SummaryVerdict.Valid(
        StoredSummary(description = "Синтетическое описание", pros = emptyList(), cons = emptyList(), tags = emptyList(), scales = emptyList()),
        SummaryLevel.MIXED, SummaryConfidence.LOW,
    ), input, "gemini-test-model", NOW)

    private fun <T> parallel(action: () -> T): List<T> {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val futures = (1..2).map { executor.submit(Callable { start.await(); action() }) }
            start.countDown()
            return futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun input(isu: Int, count: Int, version: String) = TeacherSummaryInput(isu, (1..count).map {
        SummaryInputReview(TeacherReviewKind.REVIEWS, UUID.randomUUID(), null, null, "Синтетический отзыв $it")
    }, hash(version))

    private fun hash(version: String): String = version.padStart(64, '0')

    private companion object {
        val NOW: Instant = OffsetDateTime.parse("2026-09-29T12:00:00+03:00").toInstant()
        const val ADMIN_ISU = 966001
        const val A = 966101
        const val B = 966102
        const val C = 966103
        const val D = 966104
        const val E = 966105
        const val F = 966106
    }
}
