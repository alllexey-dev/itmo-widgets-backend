package dev.alllexey.itmowidgets.backend.feature.admin.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSummaryHiddenRequest
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSummaryStatus
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialStore
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewProvider
import dev.alllexey.itmowidgets.backend.feature.reviews.model.StoredScale
import dev.alllexey.itmowidgets.backend.feature.reviews.model.StoredSummary
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryConfidence
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryLevel
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunOutcome
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunTrigger
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleKind
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleValue
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherSummaryEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherSummaryStateEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalTeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherSummaryRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherSummaryStateRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.service.AiSummaryConfig
import dev.alllexey.itmowidgets.backend.feature.reviews.service.OfficialPersonNamesSource
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherNamesService
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherSummaryService
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherSummaryViews
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleId
import dev.alllexey.itmowidgets.backend.feature.users.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

@Import(
    AdminAiSummariesService::class,
    TeacherSummaryViews::class,
    AdminAccess::class,
    AdminAuditService::class,
    AdminUserSummaries::class,
    ServiceCredentialStore::class,
    TeacherNamesService::class,
    AdminAiSummariesServiceTest.TestConfig::class,
)
// A @Bean of a @ConfigurationProperties class would be rebound, so the test binds its values instead.
@TestPropertySource(
    properties = [
        "itmowidgets.ai-summary.enabled=true", "itmowidgets.ai-summary.model=gemini-test-model",
        "itmowidgets.ai-summary.proxy-host=gemini-proxy", "itmowidgets.ai-summary.daily-request-budget=400",
    ],
)
class AdminAiSummariesServiceTest @Autowired constructor(
    private val service: AdminAiSummariesService,
    private val summaries: TeacherSummaryRepository,
    private val states: TeacherSummaryStateRepository,
    private val copies: ExternalTeacherReviewRepository,
    private val views: TeacherSummaryViews,
    private val access: AdminAccess,
    private val credentials: ServiceCredentialStore,
    private val users: AdminUserSummaries,
    private val teacherNames: TeacherNamesService,
    private val audit: AdminAuditService,
    private val config: AiSummaryConfig,
    private val transactions: PlatformTransactionManager,
    private val clock: Clock,
    private val jdbc: JdbcTemplate,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var summaryService: TeacherSummaryService

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSummaryConfig::class)
    class TestConfig {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean fun objectMapper() = jacksonObjectMapper()

        @Bean fun personNames() = OfficialPersonNamesSource { isu -> "Официальное имя $isu".takeIf { isu != NAMELESS } }
    }

    private lateinit var admin: User
    private lateinit var moderator: User

    @BeforeEach
    fun fixture() {
        admin = em.persist(
            User(isu = 969001, name = "Synthetic admin", pictureUrl = null, createdAt = NOW).apply {
                settings = UserSettingsEntity(user = this)
            },
        )
        moderator = em.persist(
            User(isu = 969002, name = "Synthetic moderator", pictureUrl = null, createdAt = NOW).apply {
                settings = UserSettingsEntity(user = this)
            },
        )
        em.persist(UserRoleEntity(UserRoleId(admin.id, UserRole.ADMIN), NOW))
        em.persistAndFlush(UserRoleEntity(UserRoleId(moderator.id, UserRole.MODERATOR), NOW))
    }

    @Test
    fun `the state counts summaries by status and shows the run and the budget of today`() {
        fourStatuses()
        states.findById(TeacherSummaryStateEntity.ID).orElseThrow().apply {
            lastStartedAt = NOW.minusSeconds(600)
            lastFinishedAt = NOW.minusSeconds(60)
            lastTrigger = SummaryRunTrigger.SCHEDULE
            lastOutcome = SummaryRunOutcome.RATE_LIMITED
            lastError = "RATE_LIMITED 429"
            lastGenerated = 2
            lastFailed = 1
            lastRequests = 4
            budgetDay = LocalDate.of(2026, 9, 29)
            budgetUsed = 12
        }
        em.flush()
        em.clear()

        val state = service.state(admin.id)

        assertTrue(state.enabled)
        assertFalse(state.running)
        assertEquals("gemini-test-model", state.model)
        assertEquals(ServiceCredentialStatus.MISSING, state.keyStatus)
        assertEquals(SummaryRunOutcome.RATE_LIMITED, state.lastOutcome)
        assertEquals("RATE_LIMITED 429", state.lastError)
        assertEquals(listOf(2, 1, 4), listOf(state.lastGenerated, state.lastFailed, state.lastRequests))
        assertEquals(listOf(1L, 1L, 1L, 1L), listOf(state.ready, state.pending, state.failed, state.hidden))
        // 06:00 UTC is still September 29 in Los Angeles.
        assertEquals(LocalDate.of(2026, 9, 29), state.budgetDay)
        assertEquals(12, state.budgetUsed)
        assertEquals(400, state.dailyBudget)

        states.findById(TeacherSummaryStateEntity.ID).orElseThrow().budgetDay = LocalDate.of(2026, 9, 28)
        em.flush()
        em.clear()
        assertEquals(0, service.state(admin.id).budgetUsed)
    }

    @Test
    fun `the key status comes from the credential row`() {
        credentials.initializeFromBootstrap(ServiceCredential.GEMINI_API_KEY, "AIza" + "0".repeat(35))
        try {
            assertEquals(ServiceCredentialStatus.UNKNOWN, service.state(admin.id).keyStatus)
        } finally {
            // The store committed the seed on its own, so the reset commits too.
            TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
                .executeWithoutResult {
                    jdbc.update(
                        "UPDATE service_credentials SET value = NULL, status = 'MISSING', updated_source = NULL " +
                            "WHERE key = 'GEMINI_API_KEY'",
                    )
                }
        }
    }

    @Test
    fun `the table filters by status, pages the most reviewed first and names teachers`() {
        fourStatuses()
        copy(READY, "Имя из Reviews")
        em.flush()
        em.clear()

        val all = service.teachers(admin.id, null, 0, 3)
        assertEquals(4, all.total)
        assertEquals(listOf(HIDDEN, FAILED, PENDING), all.items.map { it.teacherIsu })
        assertEquals(listOf(READY), service.teachers(admin.id, null, 1, 3).items.map { it.teacherIsu })

        val ready = service.teachers(admin.id, AdminSummaryStatus.READY, 0, 20).items.single()
        assertEquals("Имя из Reviews", ready.teacherName)
        assertEquals(AdminSummaryStatus.READY, ready.status)
        assertEquals(3, ready.reviewCount)
        assertEquals(SummaryLevel.MIXED, ready.summary?.level)
        val failed = service.teachers(admin.id, AdminSummaryStatus.FAILED, 0, 20).items.single()
        assertEquals("Официальное имя $FAILED", failed.teacherName)
        assertEquals(listOf(1, 6), listOf(failed.attempts, failed.inputCount))
        assertEquals("SCHEMA scales", failed.lastError)
        assertNull(failed.summary)
        val hidden = service.teachers(admin.id, AdminSummaryStatus.HIDDEN, 0, 20).items.single()
        assertNull(hidden.teacherName)
        assertTrue(hidden.hidden)
        assertEquals("Synthetic admin", hidden.hiddenByName)
        assertNotNull(hidden.summary, "An admin sees what is hidden")
        assertEquals(listOf(PENDING), service.teachers(admin.id, AdminSummaryStatus.PENDING, 0, 20).items.map { it.teacherIsu })
    }

    @Test
    fun `hiding and showing are audited once and a repeat writes nothing`() {
        row(READY, input = 3, content = true)
        em.flush()
        em.clear()

        val hidden = service.setHidden(admin.id, READY, AdminSummaryHiddenRequest(true))
        assertEquals(AdminSummaryStatus.HIDDEN, hidden.status)
        assertEquals("Synthetic admin", hidden.hiddenByName)
        assertEquals(NOW, hidden.hiddenAt)
        service.setHidden(admin.id, READY, AdminSummaryHiddenRequest(true))
        assertEquals(listOf("AI_SUMMARY_HIDDEN teacher:$READY"), audit())

        val shown = service.setHidden(admin.id, READY, AdminSummaryHiddenRequest(false))
        assertEquals(AdminSummaryStatus.READY, shown.status)
        assertNull(shown.hiddenByName)
        service.setHidden(admin.id, READY, AdminSummaryHiddenRequest(false))
        assertEquals(listOf("AI_SUMMARY_HIDDEN teacher:$READY", "AI_SUMMARY_SHOWN teacher:$READY"), audit())
        assertNotNull(views.shown(READY))

        assertFailsWith<NotFoundException> { service.setHidden(admin.id, 969999, AdminSummaryHiddenRequest(true)) }
    }

    @Test
    fun `regeneration needs an eligible shown summary and enabled summaries`() {
        fourStatuses()
        em.flush()
        em.clear()

        assertFailsWith<NotFoundException> { service.regenerate(admin.id, 969999) }
        assertEquals("Not enough reviews", assertFailsWith<BusinessRuleException> { service.regenerate(admin.id, INELIGIBLE) }.message)
        assertEquals("Summary is hidden", assertFailsWith<BusinessRuleException> { service.regenerate(admin.id, HIDDEN) }.message)
        val disabled = AdminAiSummariesService(
            access, summaries, states, copies, views, summaryService, credentials, users, teacherNames,
            audit, config.copy(enabled = false), transactions, clock,
        )
        assertEquals(
            TeacherSummaryService.DISABLED,
            assertFailsWith<BusinessRuleException> {
                disabled.regenerate(admin.id, FAILED)
            }.message,
        )
        assertEquals(emptyList(), audit())
        verify(summaryService, never()).requestTeacher(FAILED)

        doAnswer {
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT count(*) FROM teacher_summaries WHERE teacher_isu = ? AND requested_at = ? " +
                        "AND attempts = 0",
                    Int::class.java,
                    FAILED,
                    java.sql.Timestamp.from(NOW),
                ),
            )
            null
        }.`when`(summaryService).requestTeacher(FAILED)
        val requested = service.regenerate(admin.id, FAILED)

        verify(summaryService).requestTeacher(FAILED)
        assertEquals(AdminSummaryStatus.PENDING, requested.status)
        assertEquals(0, requested.attempts)
        assertEquals(listOf("AI_SUMMARY_REGENERATION_REQUESTED teacher:$FAILED"), audit())
    }

    @Test
    fun `a run starts through the summary service and a busy lease is a conflict`() {
        service.start(admin.id)
        verify(summaryService).startManual(admin.id)

        doThrow(BusinessRuleException(TeacherSummaryService.RUNNING)).`when`(summaryService).startManual(admin.id)
        assertEquals(TeacherSummaryService.RUNNING, assertFailsWith<BusinessRuleException> { service.start(admin.id) }.message)
    }

    @Test
    fun `only admins reach the summaries`() {
        row(READY, input = 3, content = true)
        em.flush()
        em.clear()
        for (call in listOf<() -> Any>(
            { service.state(moderator.id) },
            { service.start(moderator.id) },
            { service.teachers(moderator.id, null, 0, 20) },
            { service.setHidden(moderator.id, READY, AdminSummaryHiddenRequest(true)) },
            { service.regenerate(moderator.id, READY) },
        )) {
            assertFailsWith<PermissionDeniedException> { call() }
        }
        // The mocked service is an application listener, so only runs and requests are checked.
        verify(summaryService, never()).startManual(moderator.id)
        verify(summaryService, never()).requestTeacher(READY)
        assertEquals(emptyList(), audit())
    }

    /** READY with 3 reviews, PENDING with 5, FAILED with 6, HIDDEN with 7, and one row without input that is not counted. */
    private fun fourStatuses() {
        row(READY, input = 3, content = true)
        row(PENDING, input = 5, content = false)
        row(FAILED, input = 6, content = false, attempts = 1)
        row(HIDDEN, input = 7, content = true, hidden = true)
        row(INELIGIBLE, input = 0, content = false)
    }

    private fun row(isu: Int, input: Int, content: Boolean, attempts: Int = 0, hidden: Boolean = false) = em.persist(
        TeacherSummaryEntity(
            teacherIsu = isu, inputHash = if (input > 0) "b".repeat(64) else null, inputCount = input,
            content = if (content) CONTENT else null, contentHash = if (content) "b".repeat(64) else null,
            contentCount = if (content) 3 else null, level = if (content) SummaryLevel.MIXED else null,
            confidence = if (content) SummaryConfidence.LOW else null, model = if (content) "gemini-test-model" else null,
            generatedAt = if (content) NOW else null, hiddenAt = if (hidden) NOW else null, hiddenBy = if (hidden) admin.id else null,
            attempts = attempts, lastError = if (attempts > 0) "SCHEMA scales" else null, updatedAt = NOW,
        ),
    )

    private fun copy(isu: Int, name: String) = em.persist(
        ExternalTeacherReviewEntity(
            provider = ReviewProvider.REVIEWS_WORK_GD, externalId = 9_690_001, teacherIsu = isu, teacherName = name, subjectTitle = null,
            sourceTitle = null, sourceLink = null, dateRaw = "", writtenOn = null, writtenBeforeYear = null, text = "Синтетическая копия",
            firstSeenAt = NOW, lastSeenAt = NOW,
        ),
    )

    private fun audit(): List<String> = jdbc.queryForList(
        "SELECT action || ' ' || target FROM admin_audit WHERE actor_id = ? ORDER BY created_at, action",
        String::class.java,
        admin.id,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-30T06:00:00Z")
        const val READY = 969101
        const val PENDING = 969102
        const val FAILED = 969103
        const val HIDDEN = 969104
        const val INELIGIBLE = 969105
        const val NAMELESS = HIDDEN
        val CONTENT: String = jacksonObjectMapper().writeValueAsString(
            StoredSummary(
                description = "Синтетическое описание сводки.",
                pros = emptyList(),
                cons = emptyList(),
                tags = emptyList(),
                scales = SummaryScaleKind.entries.map { StoredScale(it, SummaryScaleValue.NOT_ENOUGH_DATA, null) },
            ),
        )
    }
}
