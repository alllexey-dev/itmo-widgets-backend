package dev.alllexey.itmowidgets.backend.feature.reviews.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminUserSummaries
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialStore
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunOutcome
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunTrigger
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalTeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewRevisionRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherSummaryRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReviewKind
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebLoginServiceTest
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** The stores commit on their own, so this class runs without a test transaction and cleans up after itself. */
@Import(
    TeacherSummaryService::class, TeacherSummaryStore::class, SummaryPrompt::class, SummaryValidator::class,
    ServiceCredentialStore::class, AdminAuditService::class, AdminAccess::class, AdminUserSummaries::class,
    TeacherSummaryServiceTest.TestConfig::class,
)
// A @Bean of a @ConfigurationProperties class would be rebound, so the test binds its values instead.
@TestPropertySource(
    properties = [
        "itmowidgets.ai-summary.enabled=true", "itmowidgets.ai-summary.model=gemini-test-model",
        "itmowidgets.ai-summary.proxy-host=127.0.0.1", "itmowidgets.ai-summary.daily-request-budget=10",
        "itmowidgets.ai-summary.request-delay=6s", "itmowidgets.ai-summary.api-key=synthetic-gemini-seed",
    ],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeacherSummaryServiceTest @Autowired constructor(
    private val service: TeacherSummaryService,
    private val store: TeacherSummaryStore,
    private val credentials: ServiceCredentialStore,
    private val summaries: TeacherSummaryRepository,
    private val inputs: FakeInputs,
    private val gemini: FakeGemini,
    private val pauses: RecordedPauses,
    private val clock: WebLoginServiceTest.MutableClock,
    private val config: AiSummaryConfig,
    private val jdbc: JdbcTemplate,
    private val manager: PlatformTransactionManager,
) : PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSummaryConfig::class)
    class TestConfig {
        @Bean fun fakeInputs(config: AiSummaryConfig, clock: Clock) = FakeInputs(config, clock)

        @Bean fun fakeGemini() = FakeGemini()

        @Bean fun recordedPauses() = RecordedPauses()

        @Bean fun aiSummaryExecutor(): TaskExecutor = SyncTaskExecutor()

        @Bean fun clock() = WebLoginServiceTest.MutableClock()

        @Bean fun objectMapper() = jacksonObjectMapper()
    }

    /** Teachers and their input by ISU; only eligible teachers are kept. */
    open class FakeInputs(config: AiSummaryConfig, clock: Clock) :
        SummaryInputSource(
            mock(ExternalTeacherReviewRepository::class.java),
            mock(TeacherReviewRepository::class.java),
            mock(TeacherReviewRevisionRepository::class.java),
            config,
            clock,
        ) {
        open val teachers = ConcurrentHashMap<Int, TeacherSummaryInput>()

        override fun all(): Map<Int, TeacherSummaryInput> = teachers.toMap()

        override fun forTeacher(isu: Int): TeacherSummaryInput? = teachers[isu]
    }

    /** Answers by teacher, found from the synthetic review text; records the teachers asked, in order. */
    class FakeGemini : GeminiClient {
        val calls = CopyOnWriteArrayList<Int>()
        val keys = CopyOnWriteArrayList<String>()
        val texts = CopyOnWriteArrayList<String>()
        val answers = ConcurrentHashMap<Int, () -> GeminiResponse>()
        var beforeAnswer: (Int) -> Unit = {}

        override fun generate(apiKey: String, request: GeminiRequest): GeminiResponse {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Gemini request inside a transaction")
            val teacher = TEACHER_MARK.find(request.userText)!!.groupValues[1].toInt()
            calls.add(teacher)
            keys.add(apiKey)
            texts.add(request.userText)
            beforeAnswer(teacher)
            return (answers[teacher] ?: { valid() })()
        }

        fun reset() {
            calls.clear()
            keys.clear()
            texts.clear()
            answers.clear()
            beforeAnswer = {}
        }
    }

    class RecordedPauses : SummaryPause {
        val pauses = CopyOnWriteArrayList<Duration>()
        override fun pause(duration: Duration) {
            pauses.add(duration)
        }
    }

    private lateinit var adminId: UUID
    private lateinit var logs: ListAppender<ILoggingEvent>
    private val logger = LoggerFactory.getLogger(TeacherSummaryService::class.java) as Logger

    @BeforeEach
    fun fixture() {
        cleanUp()
        inputs.teachers.clear()
        gemini.reset()
        pauses.pauses.clear()
        clock.now = NOW
        adminId = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, isu, name) VALUES (?, ?, 'Synthetic admin')", adminId, ADMIN_ISU)
        credentials.initializeFromBootstrap(ServiceCredential.GEMINI_API_KEY, KEY)
        logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
    }

    @AfterEach
    fun cleanUp() {
        if (::logs.isInitialized) {
            logger.detachAppender(logs)
            logs.stop()
        }
        jdbc.update("DELETE FROM teacher_summaries")
        jdbc.update(
            """
            UPDATE teacher_summary_state SET running_since = NULL, last_started_at = NULL, last_finished_at = NULL,
                last_trigger = NULL, last_outcome = NULL, last_error = NULL, last_generated = 0, last_failed = 0,
                last_requests = 0, budget_day = NULL, budget_used = 0
        """,
        )
        jdbc.update(
            """
            UPDATE service_credentials SET value = NULL, expires_at = NULL, status = 'MISSING', last_used_at = NULL,
                last_renewed_at = NULL, last_error_at = NULL, last_error = NULL, updated_by = NULL, updated_source = NULL
            WHERE key = 'GEMINI_API_KEY'
        """,
        )
        jdbc.update("DELETE FROM admin_audit WHERE actor_id IN (SELECT id FROM users WHERE isu = ?)", ADMIN_ISU)
        jdbc.update("DELETE FROM users WHERE isu = ?", ADMIN_ISU)
    }

    @Test
    fun `the most reviewed teachers go first and every summary becomes ready`() {
        teachers(A to 3, B to 7, C to 5)

        service.startManual(adminId)

        assertEquals(listOf(B, C, A), gemini.calls)
        assertEquals(listOf(KEY, KEY, KEY), gemini.keys)
        assertEquals(mapOf(A to "READY", B to "READY", C to "READY"), statuses())
        with(store.state()) {
            assertEquals(SummaryRunOutcome.COMPLETED, lastOutcome)
            assertEquals(SummaryRunTrigger.ADMIN, lastTrigger)
            assertEquals(listOf(3, 0, 3), listOf(lastGenerated, lastFailed, lastRequests))
            assertNull(lastError)
            assertNull(runningSince)
            assertEquals(LocalDate.of(2026, 9, 24) to 3, budgetDay to budgetUsed)
        }
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM teacher_summaries WHERE teacher_isu = ? AND content_count = 7 " +
                    "AND model = 'gemini-test-model' AND level = 'POSITIVE' AND confidence = 'HIGH'",
                Int::class.java,
                B,
            ),
        )
        // Three reviews never make a confident summary.
        assertEquals("LOW", jdbc.queryForObject("SELECT confidence FROM teacher_summaries WHERE teacher_isu = ?", String::class.java, A))
        with(credentials.state(ServiceCredential.GEMINI_API_KEY)) {
            assertEquals("OK", status.name)
            assertEquals(NOW, lastUsedAt)
        }
        assertEquals(listOf("AI_SUMMARIES_RUN_STARTED ai-summaries"), audit())
    }

    @Test
    fun `a run without changes asks nothing`() {
        teachers(A to 3, B to 7)
        service.scheduledRun()
        gemini.calls.clear()
        clock.now = NOW.plusSeconds(3600)

        service.scheduledRun()

        assertEquals(emptyList(), gemini.calls)
        assertEquals(SummaryRunOutcome.COMPLETED, store.state().lastOutcome)
        assertEquals(SummaryRunTrigger.SCHEDULE, store.state().lastTrigger)
        assertEquals(0, store.state().lastRequests)
    }

    @Test
    fun `a changed input is asked again while the previous summary stays shown`() {
        teachers(A to 3, B to 7)
        service.scheduledRun()
        gemini.calls.clear()
        clock.now = NOW.plusSeconds(3600)
        teachers(A to 4)
        gemini.beforeAnswer = { teacher ->
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT count(*) FROM teacher_summaries WHERE teacher_isu = ? AND content_count = 3 " +
                        "AND input_count = 4 AND content_hash <> input_hash",
                    Int::class.java,
                    teacher,
                ),
            )
        }

        service.scheduledRun()

        assertEquals(listOf(A), gemini.calls)
        assertEquals(mapOf(A to "READY", B to "READY"), statuses())
        assertEquals(4, jdbc.queryForObject("SELECT content_count FROM teacher_summaries WHERE teacher_isu = ?", Int::class.java, A))
    }

    @Test
    fun `an exhausted budget stops the run and the next Pacific day finishes it`() {
        teachers(A to 3, B to 7, C to 5)
        jdbc.update("UPDATE teacher_summary_state SET budget_day = ?, budget_used = 8", LocalDate.of(2026, 9, 24))

        service.scheduledRun()

        assertEquals(listOf(B, C), gemini.calls)
        assertEquals(SummaryRunOutcome.BUDGET_EXHAUSTED, store.state().lastOutcome)
        assertEquals(mapOf(A to "PENDING", B to "READY", C to "READY"), statuses())

        gemini.calls.clear()
        clock.now = Instant.parse("2026-09-25T07:30:00Z")
        service.scheduledRun()

        assertEquals(listOf(A), gemini.calls)
        assertEquals(SummaryRunOutcome.COMPLETED, store.state().lastOutcome)
        assertEquals(LocalDate.of(2026, 9, 25) to 1, store.state().let { it.budgetDay to it.budgetUsed })
    }

    @Test
    fun `a rejected answer keeps the previous summary, counts an attempt and the run goes on`() {
        teachers(A to 7)
        service.scheduledRun()
        teachers(A to 8, B to 5)
        gemini.calls.clear()
        gemini.answers[A] = { valid(finishReason = "MAX_TOKENS") }

        for (run in 1..3) {
            clock.now = NOW.plusSeconds(3600L * run)
            service.scheduledRun()
        }

        assertEquals(listOf(A, B, A, A), gemini.calls)
        assertEquals(mapOf(A to "FAILED", B to "READY"), statuses())
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM teacher_summaries WHERE teacher_isu = ? AND attempts = 3 " +
                    "AND last_error = 'FINISH_MAX_TOKENS' AND content_count = 7",
                Int::class.java,
                A,
            ),
        )
        assertEquals(SummaryRunOutcome.COMPLETED, store.state().lastOutcome)
        assertEquals(listOf(0, 1, 1), store.state().let { listOf(it.lastGenerated, it.lastFailed, it.lastRequests) })

        gemini.calls.clear()
        clock.now = NOW.plusSeconds(3600L * 4)
        service.scheduledRun()
        assertEquals(emptyList(), gemini.calls)

        // «Пересчитать всё» gives failed teachers fresh attempts.
        clock.now = NOW.plusSeconds(3600L * 5)
        gemini.answers.remove(A)
        service.startManual(adminId)
        assertEquals(listOf(A), gemini.calls)
        assertEquals(mapOf(A to "READY", B to "READY"), statuses())
    }

    @Test
    fun `Gemini failures stop the run without counting an attempt`() {
        teachers(A to 7, B to 5)
        for ((index, case) in listOf(
            GeminiFailure(GeminiErrorCategory.RATE_LIMITED, 429) to (SummaryRunOutcome.RATE_LIMITED to "RATE_LIMITED 429"),
            GeminiFailure(GeminiErrorCategory.AUTH, 403) to (SummaryRunOutcome.AUTH_FAILED to "AUTH 403"),
            GeminiFailure(GeminiErrorCategory.NETWORK) to (SummaryRunOutcome.FAILED to "NETWORK"),
            GeminiFailure(GeminiErrorCategory.LOCATION, 400) to (SummaryRunOutcome.FAILED to "LOCATION 400"),
        ).withIndex()) {
            val (failure, expected) = case
            gemini.calls.clear()
            gemini.answers[A] = { throw failure }
            clock.now = NOW.plusSeconds(3600L * index)

            service.scheduledRun()

            assertEquals(listOf(A), gemini.calls, expected.second)
            assertEquals(expected, store.state().let { it.lastOutcome to it.lastError })
            assertEquals(0, jdbc.queryForObject("SELECT attempts FROM teacher_summaries WHERE teacher_isu = ?", Int::class.java, A))
            assertEquals(mapOf(A to "PENDING", B to "PENDING"), statuses())
            if (failure.category == GeminiErrorCategory.AUTH) {
                val key = credentials.state(ServiceCredential.GEMINI_API_KEY)
                assertEquals("FAILED", key.status.name)
                assertEquals("AUTH 403", key.lastError)
            }
        }
    }

    @Test
    fun `without a key nothing is asked and the seed fills only an empty key`() {
        teachers(A to 3)
        jdbc.update("UPDATE service_credentials SET value = NULL, status = 'MISSING', updated_source = NULL WHERE key = 'GEMINI_API_KEY'")

        service.scheduledRun()

        assertEquals(emptyList(), gemini.calls)
        assertEquals(SummaryRunOutcome.NO_KEY, store.state().lastOutcome)
        assertEquals(mapOf(A to "PENDING"), statuses())

        jdbc.update("UPDATE teacher_summary_state SET running_since = now()")
        service.onApplicationEvent(mock(ApplicationReadyEvent::class.java))
        assertNull(store.state().runningSince)
        assertEquals("synthetic-gemini-seed", credentials.value(ServiceCredential.GEMINI_API_KEY))
        assertEquals("SEED", credentials.state(ServiceCredential.GEMINI_API_KEY).updatedSource?.name)

        credentials.initializeFromBootstrap(ServiceCredential.GEMINI_API_KEY, "synthetic-other-seed")
        service.onApplicationEvent(mock(ApplicationReadyEvent::class.java))
        assertEquals("synthetic-gemini-seed", credentials.value(ServiceCredential.GEMINI_API_KEY))
    }

    @Test
    fun `disabled summaries and a held lease start nothing`() {
        teachers(A to 3)
        val disabled = TeacherSummaryService(
            inputs, SummaryPrompt(jacksonObjectMapper()), SummaryValidator(jacksonObjectMapper()),
            gemini, store, credentials, config.copy(enabled = false), SyncTaskExecutor(), clock, pauses,
        )
        disabled.scheduledRun()
        disabled.requestTeacher(A)
        assertEquals(TeacherSummaryService.DISABLED, assertFailsWith<BusinessRuleException> { disabled.startManual(adminId) }.message)
        assertNull(store.state().lastStartedAt)

        jdbc.update("UPDATE teacher_summary_state SET running_since = ?", java.sql.Timestamp.from(NOW))
        assertEquals(TeacherSummaryService.RUNNING, assertFailsWith<BusinessRuleException> { service.startManual(adminId) }.message)
        service.scheduledRun()
        service.requestTeacher(A)

        assertEquals(emptyList(), gemini.calls)
        assertEquals(emptyList(), audit())
        assertNull(store.state().lastOutcome)
        assertNotNull(store.state().runningSince)
    }

    @Test
    fun `hidden teachers are skipped and ineligible ones lose their summary`() {
        teachers(A to 3, B to 7)
        service.scheduledRun()
        gemini.calls.clear()
        TransactionTemplate(manager).executeWithoutResult { summaries.setHidden(A, NOW, adminId, NOW) }
        teachers(A to 4)
        inputs.teachers.remove(B)
        clock.now = NOW.plusSeconds(3600)

        service.scheduledRun()

        assertEquals(emptyList(), gemini.calls)
        assertEquals(mapOf(A to "HIDDEN"), statuses())
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM teacher_summaries WHERE teacher_isu = ? AND input_hash IS NULL " +
                    "AND content IS NULL AND level IS NULL",
                Int::class.java,
                B,
            ),
        )
    }

    @Test
    fun `a teacher requested during a run is taken next, and without a run starts one`() {
        teachers(A to 3)
        service.scheduledRun()
        teachers(B to 7, C to 5)
        gemini.calls.clear()
        clock.now = NOW.plusSeconds(3600)
        gemini.beforeAnswer = { teacher ->
            if (teacher == B) {
                TransactionTemplate(manager).executeWithoutResult { summaries.request(A, clock.instant()) }
                service.requestTeacher(A)
            }
        }

        service.scheduledRun()

        assertEquals(listOf(B, A, C), gemini.calls)
        assertEquals(mapOf(A to "READY", B to "READY", C to "READY"), statuses())

        gemini.calls.clear()
        gemini.beforeAnswer = {}
        clock.now = NOW.plusSeconds(7200)
        TransactionTemplate(manager).executeWithoutResult { summaries.request(C, clock.instant()) }
        service.requestTeacher(C)

        assertEquals(listOf(C), gemini.calls)
        assertEquals(SummaryRunTrigger.ADMIN, store.state().lastTrigger)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM teacher_summaries WHERE requested_at IS NOT NULL", Int::class.java))
    }

    @Test
    fun `requests are paced by the configured delay except the first`() {
        teachers(A to 3, B to 7, C to 5)

        service.scheduledRun()

        assertEquals(3, gemini.calls.size)
        assertEquals(listOf(Duration.ofSeconds(6), Duration.ofSeconds(6)), pauses.pauses)
    }

    @Test
    fun `logs carry counters and codes but no review text, prompt, answer or key`() {
        teachers(A to 3, B to 7, C to 5)
        gemini.answers[C] = { valid(description = "Синтетический ответ модели со ссылкой https://example.com") }
        gemini.answers[A] = { throw GeminiFailure(GeminiErrorCategory.HTTP, 503) }

        service.scheduledRun()

        val lines = logs.list.map { event -> event.formattedMessage + (event.throwableProxy?.message ?: "") }
        assertTrue(lines.any { it == "AI summary of teacher $C rejected: FORBIDDEN description" }, lines.toString())
        assertTrue(lines.any { it == "Gemini FAILED HTTP 503" }, lines.toString())
        assertTrue(
            lines.any {
                it.startsWith("AI summaries FAILED trigger=SCHEDULE generated=1 failed=1 requests=3 budget=3/10 durationMs=")
            },
            lines.toString(),
        )
        val secrets = listOf(KEY, "Синтетический отзыв", "Синтетический ответ", "https://example.com", "<<<", "Отзывов:") +
            gemini.texts
        for (line in lines) {
            secrets.forEach { assertFalse(it in line, line) }
        }
    }

    private fun teachers(vararg counts: Pair<Int, Int>) {
        for ((isu, count) in counts) inputs.teachers[isu] = input(isu, count)
    }

    private fun input(isu: Int, count: Int): TeacherSummaryInput {
        val reviews = (1..count).map {
            SummaryInputReview(
                TeacherReviewKind.REVIEWS,
                UUID.nameUUIDFromBytes("$isu-$it".toByteArray()),
                "Предмет",
                "2026-09",
                "Синтетический отзыв $it о преподавателе $isu",
            )
        }
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("$isu-$count".toByteArray()))
        return TeacherSummaryInput(isu, reviews, hash)
    }

    private fun statuses(): Map<Int, String> = jdbc.query(
        """
        SELECT teacher_isu, CASE WHEN hidden_at IS NOT NULL THEN 'HIDDEN' WHEN content_hash = input_hash THEN 'READY'
            WHEN attempts > 0 THEN 'FAILED' ELSE 'PENDING' END
        FROM teacher_summaries WHERE input_hash IS NOT NULL OR hidden_at IS NOT NULL
    """,
    ) { rs, _ -> rs.getInt(1) to rs.getString(2) }.toMap()

    private fun audit(): List<String> = jdbc.queryForList(
        "SELECT action || ' ' || target FROM admin_audit WHERE actor_id = ?",
        String::class.java,
        adminId,
    )

    companion object {
        private val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
        private const val ADMIN_ISU = 967001
        private const val A = 967101
        private const val B = 967102
        private const val C = 967103

        /** Built from parts, so a search for leaked keys stays empty. */
        private val KEY = "AIza" + "0".repeat(35)
        private val TEACHER_MARK = Regex("о преподавателе (\\d+)")

        fun valid(
            finishReason: String = "STOP",
            description: String = "Студенты отмечают понятные лекции и доброжелательное отношение преподавателя.",
        ): GeminiResponse {
            val answer = jacksonObjectMapper().writeValueAsString(
                mapOf(
                    "description" to description,
                    "pros" to listOf("Понятные лекции"),
                    "cons" to emptyList<String>(),
                    "tags" to listOf(mapOf("code" to "AUTOMAT", "evidence" to 1)),
                    "scales" to mapOf(
                        "explains" to mapOf("value" to "HIGH", "reason" to "Лекции понятные"),
                        "attitude" to mapOf("value" to "HIGH", "reason" to "Доброжелательный"),
                        "fairness" to mapOf("value" to "NOT_ENOUGH_DATA", "reason" to ""),
                        "strictness" to mapOf("value" to "MEDIUM", "reason" to "Строгость умеренная"),
                        "workload" to mapOf("value" to "NOT_ENOUGH_DATA", "reason" to ""),
                    ),
                    "level" to "POSITIVE",
                    "confidence" to "HIGH",
                ),
            )
            return GeminiResponse(null, listOf(GeminiCandidate(finishReason, answer)), 1000, 300, null)
        }
    }
}
