package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.admin.model.AdminAuditEntity
import dev.alllexey.itmowidgets.backend.feature.admin.persistence.AdminAuditRepository
import dev.alllexey.itmowidgets.backend.feature.admin.persistence.AdminRestrictionRow
import dev.alllexey.itmowidgets.backend.feature.admin.persistence.LabelCount
import dev.alllexey.itmowidgets.backend.feature.admin.persistence.SportOutcomeRow
import dev.alllexey.itmowidgets.backend.feature.admin.persistence.TargetCountRow
import dev.alllexey.itmowidgets.backend.feature.admin.persistence.UserGroupRow
import dev.alllexey.itmowidgets.backend.feature.admin.persistence.UserSummaryRow
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAiSummariesService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminDashboardService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminModerationService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminRestrictionViews
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminReviewsService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminSystemService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminUserSummaries
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminUsersService
import dev.alllexey.itmowidgets.backend.feature.app.model.AppSettingEntity
import dev.alllexey.itmowidgets.backend.feature.app.persistence.AppSettingRepository
import dev.alllexey.itmowidgets.backend.feature.app.service.AppVersionSettings
import dev.alllexey.itmowidgets.backend.feature.credentials.model.CredentialSource
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialEntity
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.feature.credentials.persistence.ServiceCredentialRepository
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialStore
import dev.alllexey.itmowidgets.backend.feature.links.persistence.SubjectLinkRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.moderation.model.UserRestrictionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationCaseRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationDecisionRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationReportRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationSettingRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.UserRestrictionRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.service.CaseTargetSummary
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationFixture
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationSettingsService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationTarget
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationTargets
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModeratorAccess
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCaseTarget
import dev.alllexey.itmowidgets.backend.feature.push.persistence.DeviceRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalReviewSyncStateEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewProvider
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewSyncOutcome
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewVerification
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunOutcome
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunTrigger
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherSummaryEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherSummaryStateEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalReviewSyncStateRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalTeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherNameRow
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherSummaryRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherSummaryStateRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.service.AiSummaryConfig
import dev.alllexey.itmowidgets.backend.feature.reviews.service.ReviewsSyncConfig
import dev.alllexey.itmowidgets.backend.feature.reviews.service.ReviewsSyncService
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherNamesService
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherSummaryService
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherSummaryViews
import dev.alllexey.itmowidgets.backend.feature.social.persistence.FriendshipRepository
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateLog
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateOutcome
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportUpdateLogRepository
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleId
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRoleRepository
import dev.alllexey.itmowidgets.backend.feature.users.service.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.feature.users.web.UserCapabilities
import dev.alllexey.itmowidgets.backend.feature.users.web.UserData
import dev.alllexey.itmowidgets.backend.feature.weblogin.persistence.WebSessionRepository
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.ActiveWebSession
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.platform.security.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import dev.alllexey.itmowidgets.backend.testing.TestUsers
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The role matrix of every admin route with the real services and access rules; only persistence is mocked. */
@WebMvcTest(
    AdminModerationController::class,
    AdminUsersController::class,
    AdminDashboardController::class,
    AdminSystemController::class,
    AdminAuditController::class,
    AdminReviewsController::class,
)
@Import(
    SecurityConfig::class, GlobalExceptionHandler::class, AdminAccess::class, ModeratorAccess::class, ModerationService::class,
    RestrictionService::class, ModerationSettingsService::class, AdminModerationService::class, AdminUsersService::class,
    AdminDashboardService::class, AdminSystemService::class, AdminAuditService::class, AdminUserSummaries::class,
    AdminRestrictionViews::class, AppVersionSettings::class, AdminReviewsService::class, ServiceCredentialStore::class,
    AdminAiSummariesService::class, TeacherSummaryViews::class, AdminApiSecurityTest.TimeConfig::class,
)
// A @Bean of a @ConfigurationProperties class would be rebound, so the test binds its values instead.
@TestPropertySource(
    properties = [
        "itmowidgets.ai-summary.enabled=true", "itmowidgets.ai-summary.model=gemini-test-model",
        "itmowidgets.ai-summary.proxy-host=gemini-proxy", "itmowidgets.ai-summary.daily-request-budget=400",
    ],
)
class AdminApiSecurityTest @Autowired constructor(private val mvc: MockMvc, private val json: ObjectMapper) {
    @MockitoBean private lateinit var jwt: JwtAuthFilter

    @MockitoBean private lateinit var webSessions: WebSessionService

    @MockitoBean private lateinit var roles: UserRoleRepository

    @MockitoBean private lateinit var users: UserRepository

    @MockitoBean private lateinit var cases: ModerationCaseRepository

    @MockitoBean private lateinit var decisions: ModerationDecisionRepository

    @MockitoBean private lateinit var reports: ModerationReportRepository

    @MockitoBean private lateinit var restrictions: UserRestrictionRepository

    @MockitoBean private lateinit var moderationSettings: ModerationSettingRepository

    @MockitoBean private lateinit var targets: ModerationTargets

    @MockitoBean private lateinit var currentGroups: CurrentStudyGroupsService

    @MockitoBean private lateinit var devices: DeviceRepository

    @MockitoBean private lateinit var friendships: FriendshipRepository

    @MockitoBean private lateinit var links: SubjectLinkRepository

    @MockitoBean private lateinit var sessions: WebSessionRepository

    @MockitoBean private lateinit var autoSign: SportAutoSignEntryRepository

    @MockitoBean private lateinit var freeSign: SportFreeSignEntryRepository

    @MockitoBean private lateinit var sportLogs: SportUpdateLogRepository

    @MockitoBean private lateinit var appSettings: AppSettingRepository

    @MockitoBean private lateinit var audit: AdminAuditRepository

    @MockitoBean private lateinit var reviewsSync: ReviewsSyncService

    @MockitoBean private lateinit var reviews: ExternalTeacherReviewRepository

    @MockitoBean private lateinit var reviewStates: ExternalReviewSyncStateRepository

    @MockitoBean private lateinit var credentialRows: ServiceCredentialRepository

    @MockitoBean private lateinit var ownReviews: TeacherReviewRepository

    @MockitoBean private lateinit var teacherNames: TeacherNamesService

    @MockitoBean private lateinit var summaryRows: TeacherSummaryRepository

    @MockitoBean private lateinit var summaryStates: TeacherSummaryStateRepository

    @MockitoBean private lateinit var summaryService: TeacherSummaryService

    // The web slice has no transaction manager; the service's template only needs one to exist.
    @MockitoBean private lateinit var transactions: PlatformTransactionManager

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReviewsSyncConfig::class, AiSummaryConfig::class)
    class TimeConfig {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    private val admin = TestUsers.user(970100, createdAt = ModerationFixture.now)
    private val moderator = TestUsers.user(970200, createdAt = ModerationFixture.now)
    private val student = TestUsers.user(970300, createdAt = ModerationFixture.now)
    private val author = TestUsers.user(AUTHOR_ISU, createdAt = ModerationFixture.now)
    private val case = ModerationFixture.case()
    private val decision = ModerationDecisionEntity(
        case = case,
        moderator = moderator,
        action = ModerationAction.RESTRICT_USER,
        restrictionCapability = RestrictionCapability.VOTE,
        restrictionDays = 7,
        createdAt = NOW,
    )
    private val restriction = UserRestrictionEntity(
        user = author,
        capability = RestrictionCapability.VOTE,
        decision = decision,
        reason = "Спам",
        startsAt = NOW,
        expiresAt = NOW.plusSeconds(86_400),
    )
    private val target = object : ModerationTarget {
        override fun targetType() = ModerationTargetType.SUBJECT_RESOURCE
        override fun ownerId(targetId: UUID) = author.id
        override fun isReportable(targetId: UUID, reporterId: UUID) = true
        override fun apply(action: ModerationAction, targetId: UUID, decision: ModerationDecisionEntity) = Unit
        override fun describe(targetId: UUID, viewerId: UUID): ModerationCaseTarget = ModerationFixture.linkTarget(targetId, author)
        override fun summaries(targetIds: Collection<UUID>) = targetIds.associateWith { id ->
            CaseTargetSummary(
                ModerationFixture.linkTarget(id, author).revision,
                AdminLinkSummary(UUID.randomUUID(), 42, "Предмет", "2026-1", 3, hidden = false),
                author.id,
            )
        }
    }

    @BeforeEach
    fun fixture() {
        // Spring 7 declares the status non-null, so TransactionTemplate's callback needs a real one from the mock.
        `when`(transactions.getTransaction(nullable(TransactionDefinition::class.java))).thenReturn(SimpleTransactionStatus())
        doAnswer {
            it.getArgument<FilterChain>(2).doFilter(it.getArgument(0), it.getArgument(1))
            null
        }.`when`(jwt).doFilter(any(), any(), any())
        `when`(roles.existsByUserIdAndRole(admin.id, UserRole.ADMIN)).thenReturn(true)
        `when`(roles.existsByUserIdAndRole(moderator.id, UserRole.MODERATOR)).thenReturn(true)
        `when`(webSessions.resolve(SESSION)).thenReturn(ActiveWebSession(admin.id, freshForAdmin = true))
        for (actor in listOf(admin, moderator)) `when`(users.findById(actor.id)).thenReturn(Optional.of(actor))
        `when`(users.findSummaryRows(anyCollection())).thenAnswer { invocation ->
            val ids = invocation.getArgument<Collection<UUID>>(0)
            listOf(admin, moderator, author).filter {
                it.id in ids
            }.map { UserSummaryRow(it.id, it.isu, it.name, it.pictureUrl, it.createdAt) }
        }
        `when`(users.findGroupRows(anyCollection())).thenReturn(listOf(UserGroupRow(author.id, "P3219", 2, "ФПИиКТ")))
        `when`(users.findIdByIsu(AUTHOR_ISU)).thenReturn(author.id)
        `when`(users.search(nullable(String::class.java), anyString(), any(Pageable::class.java) ?: PAGE)).thenAnswer {
            PageImpl(listOf(UserSummaryRow(author.id, author.isu, author.name, null, NOW)), it.getArgument(2), 1)
        }
        `when`(roles.findRoleIdsOf(anyCollection())).thenReturn(listOf(UserRoleId(author.id, UserRole.MODERATOR)))
        `when`(roles.grant(author.id, "MODERATOR", NOW)).thenReturn(1)
        `when`(roles.revoke(author.id, UserRole.MODERATOR)).thenReturn(1)
        `when`(devices.findAdminDevices(author.id)).thenReturn(listOf(AdminDevice("Pixel", NOW)))
        `when`(sessions.findLastSeen(author.id)).thenReturn(NOW)

        `when`(
            cases.findPage(
                any(ModerationCaseStatus::class.java) ?: ModerationCaseStatus.OPEN,
                nullable(ModerationCaseReason::class.java),
                any(Pageable::class.java) ?: PAGE,
            ),
        ).thenAnswer {
            PageImpl(listOf(case), it.getArgument(2), 1)
        }
        `when`(cases.findById(case.id)).thenReturn(Optional.of(case))
        `when`(cases.lockById(case.id)).thenReturn(case.id)
        `when`(targets.forType(ModerationTargetType.SUBJECT_RESOURCE)).thenReturn(target)
        `when`(reports.countActiveByTargets(ModerationTargetType.SUBJECT_RESOURCE, listOf(case.targetId)))
            .thenReturn(listOf(TargetCountRow(case.targetId, 2)))
        doAnswer { it.getArgument<ModerationDecisionEntity>(0) }.`when`(decisions).save(any())
        `when`(
            restrictions.findAdminPage(
                nullable(Int::class.javaObjectType),
                anyBoolean(),
                any(Instant::class.java) ?: NOW,
                any(Pageable::class.java) ?: PAGE,
            ),
        ).thenAnswer {
            PageImpl(
                listOf(
                    AdminRestrictionRow(
                        restriction.id, author.id, restriction.capability, restriction.reason, NOW,
                        restriction.expiresAt, null, null, case.id,
                    ),
                ),
                it.getArgument(3),
                1,
            )
        }
        `when`(restrictions.findById(restriction.id)).thenReturn(Optional.of(restriction))
        doAnswer { it.getArgument<UserData>(0) }.`when`(currentGroups).userData(any(UserData::class.java) ?: SAMPLE)

        `when`(sportLogs.findTop50ByOrderByUpdateTimestampDescIdDesc()).thenReturn(
            listOf(
                SportUpdateLog(
                    id = 7, updateTimestamp = NOW,
                    outcome = SportUpdateOutcome.FAILED, durationMillis = 1200,
                    receivedLessons = 0, newLessonsAdded = 0, updatedLessons = 0,
                    skippedLessons = 0, errorCategory = SportUpdateErrorCategory.NETWORK,
                ),
            ),
        )
        `when`(
            sportLogs.countOutcomesSince(any(Instant::class.java) ?: NOW),
        ).thenReturn(listOf(SportOutcomeRow(SportUpdateOutcome.FAILED, 1, 1200)))
        `when`(audit.findPage(any(Pageable::class.java) ?: PAGE)).thenAnswer {
            PageImpl(
                listOf(
                    AdminAuditEntity(
                        actorId = admin.id,
                        action = "ROLE_GRANTED",
                        target = "user:$AUTHOR_ISU",
                        details = "role MODERATOR",
                        createdAt = NOW,
                    ),
                ),
                it.getArgument(0),
                1,
            )
        }
        val storedCredentials = ServiceCredential.entries.map { credential ->
            ServiceCredentialEntity(
                credential.name,
                value = "$STORED_VALUE-${credential.name}",
                status = ServiceCredentialStatus.OK,
                updatedAt = NOW,
                updatedSource = CredentialSource.SEED,
            )
        }
        `when`(credentialRows.findAll()).thenReturn(storedCredentials)
        `when`(credentialRows.lockAll(anyCollection())).thenAnswer { invocation ->
            val keys = invocation.getArgument<Collection<String>>(0)
            storedCredentials.filter { it.key in keys }
        }
        `when`(ownReviews.countByVerification(ReviewVerification.PENDING)).thenReturn(3)
        `when`(ownReviews.countByVerification(ReviewVerification.VERIFIED)).thenReturn(5)
        `when`(ownReviews.countByVerification(ReviewVerification.UNVERIFIED)).thenReturn(1)
        `when`(credentialRows.findById(ServiceCredential.GEMINI_API_KEY.name))
            .thenReturn(Optional.of(storedCredentials.single { it.key == ServiceCredential.GEMINI_API_KEY.name }))
        `when`(summaryStates.findById(TeacherSummaryStateEntity.ID)).thenReturn(
            Optional.of(
                TeacherSummaryStateEntity(
                    lastStartedAt = NOW,
                    lastFinishedAt = NOW,
                    lastTrigger = SummaryRunTrigger.ADMIN,
                    lastOutcome = SummaryRunOutcome.COMPLETED,
                    lastGenerated = 3,
                    lastRequests = 3,
                    budgetDay = java.time.LocalDate.of(2026, 9, 24),
                    budgetUsed = 3,
                ),
            ),
        )
        `when`(summaryRows.countByStatus()).thenReturn(listOf(labelCount("READY", 3), labelCount("FAILED", 1)))
        val summaryRow = TeacherSummaryEntity(
            teacherIsu = TEACHER_ISU,
            inputHash = "b".repeat(64),
            inputCount = 4,
            updatedAt = NOW,
            attempts = 1,
            lastError = "SCHEMA scales",
        )
        `when`(summaryRows.findAdminPage(nullable(String::class.java), any(Pageable::class.java) ?: PAGE)).thenAnswer {
            PageImpl(listOf(summaryRow), it.getArgument(1), 1)
        }
        `when`(summaryRows.findById(TEACHER_ISU)).thenReturn(Optional.of(summaryRow))
        `when`(reviews.findActiveTeacherNames(anyString(), anyCollection())).thenReturn(
            listOf(object : TeacherNameRow {
                override val teacherIsu = TEACHER_ISU
                override val teacherName = "Синтетический преподаватель"
            }),
        )
        `when`(reviewStates.findById(ReviewProvider.REVIEWS_WORK_GD)).thenReturn(
            Optional.of(
                ExternalReviewSyncStateEntity(
                    provider = ReviewProvider.REVIEWS_WORK_GD,
                    lastCheckedAt = NOW,
                    lastOutcome = ReviewSyncOutcome.FAILED,
                    lastError = "HTTP 503 /teacher/100123",
                ),
            ),
        )
    }

    private fun moderationRoutes(): List<MockHttpServletRequestBuilder> = listOf(
        get("/api/admin/moderation/cases"),
        get("/api/admin/moderation/cases?status=RESOLVED&reason=REPORTS&page=0&size=5"),
        get("/api/admin/moderation/cases/${case.id}"),
        get("/api/admin/moderation/restrictions?isu=$AUTHOR_ISU&active=false"),
        get("/api/admin/moderation/restrictions"),
        post("/api/admin/moderation/restrictions/${restriction.id}/revoke"),
        post("/api/admin/moderation/cases/${case.id}/decisions").content("""{"action":"APPROVE"}"""),
    )

    private fun adminRoutes(): List<MockHttpServletRequestBuilder> = listOf(
        get("/api/admin/moderation/settings"),
        put("/api/admin/moderation/settings").content(POLICY),
        get("/api/admin/users?query=P32&page=0&size=10"),
        get("/api/admin/users/$AUTHOR_ISU"),
        put("/api/admin/users/$AUTHOR_ISU/roles/MODERATOR"),
        delete("/api/admin/users/$AUTHOR_ISU/roles/MODERATOR"),
        get("/api/admin/dashboard"),
        get("/api/admin/system/sport"),
        get("/api/admin/system/app-version"),
        put("/api/admin/system/app-version").content("""{"latest":"2.3","minimum":"2.1","note":"Новое"}"""),
        get("/api/admin/system/app-version?platform=IOS"),
        put("/api/admin/system/app-version?platform=IOS").content("""{"latest":"2.4","minimum":"2.3"}"""),
        get("/api/admin/audit?page=0&size=10"),
        get("/api/admin/reviews/sync"),
        post("/api/admin/reviews/sync"),
        get("/api/admin/reviews/verification"),
        get("/api/admin/system/credentials"),
        put("/api/admin/system/credentials/ISU_KEYCLOAK_IDENTITY").content(CREDENTIAL_REQUEST),
        get("/api/admin/reviews/summaries"),
        post("/api/admin/reviews/summaries/run"),
        get("/api/admin/reviews/summaries/teachers?status=FAILED&page=0&size=10"),
        put("/api/admin/reviews/summaries/$TEACHER_ISU/hidden").content("""{"hidden":true}"""),
        post("/api/admin/reviews/summaries/$TEACHER_ISU/regenerate"),
    )

    @Test
    fun `anonymous callers are denied every admin route before services`() {
        (moderationRoutes() + adminRoutes()).forEach {
            mvc.perform(it.contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized).andExpect(jsonPath("$.error.code").value("unauthorized"))
        }
        verifyNoInteractions(
            roles, users, cases, decisions, reports, restrictions, moderationSettings, devices, friendships, links,
            sessions, autoSign, freeSign, sportLogs, appSettings, audit, reviews, reviewStates, credentialRows, ownReviews, summaryRows,
            summaryStates,
        )
    }

    @Test
    fun `a regular user is denied every admin route without data access`() {
        (moderationRoutes() + adminRoutes()).forEach {
            mvc.perform(it.contentType(MediaType.APPLICATION_JSON).with(user(student.id.toString())))
                .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("permission_denied"))
        }
        verifyNoInteractions(
            users, cases, decisions, reports, restrictions, moderationSettings, devices, friendships, links,
            sessions, autoSign, freeSign, sportLogs, appSettings, audit, reviews, reviewStates, credentialRows, ownReviews, summaryRows,
            summaryStates,
        )
    }

    @Test
    fun `a moderator works the moderation queue but no admin route`() {
        moderationRoutes().forEach {
            mvc.perform(it.contentType(MediaType.APPLICATION_JSON).with(user(moderator.id.toString())))
                .andExpect(status().isOk).andExpect(jsonPath("$.success").value(true))
        }
        adminRoutes().forEach {
            mvc.perform(it.contentType(MediaType.APPLICATION_JSON).with(user(moderator.id.toString())))
                .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("permission_denied"))
        }
        verify(roles, never()).grant(any(UUID::class.java) ?: admin.id, anyString(), any(Instant::class.java) ?: NOW)
        verify(moderationSettings, never()).upsert(
            anyString(),
            anyString(),
            any(Instant::class.java) ?: NOW,
            any(UUID::class.java) ?: admin.id,
        )
        verifyNoInteractions(
            devices, friendships, links, sessions, sportLogs, appSettings, audit, reviews, reviewStates, credentialRows, ownReviews,
            summaryRows, summaryStates,
        )
    }

    @Test
    fun `an admin reaches every route and the responses have exact camelCase keys`() {
        val queue = data(get("/api/admin/moderation/cases"))
        assertEquals(PAGE_KEYS, queue.keys())
        assertEquals(
            setOf("id", "targetType", "status", "reason", "openedAt", "resolvedAt", "revision", "link", "review", "author", "reportCount"),
            queue["items"][0].keys(),
        )
        assertEquals(2, queue["items"][0]["reportCount"].asInt())
        assertEquals(setOf("id", "subjectId", "subjectName", "periodKey", "score", "hidden"), queue["items"][0]["link"].keys())
        assertEquals(REVISION_KEYS, queue["items"][0]["revision"].keys())
        assertEquals(USER_KEYS, queue["items"][0]["author"].keys())
        assertEquals(setOf("name", "course", "facultyShortName"), queue["items"][0]["author"]["groups"][0].keys())
        assertEquals(listOf(0, 20, 1), listOf(queue["page"].asInt(), queue["size"].asInt(), queue["total"].asInt()))

        val detail = data(get("/api/admin/moderation/cases/${case.id}"))
        assertEquals(setOf("id", "targetType", "status", "reason", "openedAt", "target", "decisions"), detail.keys())
        assertEquals(setOf("targetType", "revision", "link", "author", "reports", "submitterHistory"), detail["target"].keys())

        val restrictionPage = data(get("/api/admin/moderation/restrictions?isu=$AUTHOR_ISU&active=false"))
        assertEquals(
            setOf("id", "user", "capability", "reason", "startsAt", "expiresAt", "revokedAt", "revokedByIsu", "active", "caseId"),
            restrictionPage["items"][0].keys(),
        )
        assertTrue(restrictionPage["items"][0]["active"].asBoolean())

        assertEquals(setOf("policies"), data(get("/api/admin/moderation/settings")).keys())
        assertEquals(setOf("policies"), data(put("/api/admin/moderation/settings").content(POLICY)).keys())

        val usersPage = data(get("/api/admin/users?query=P32"))
        assertEquals(PAGE_KEYS, usersPage.keys())
        assertEquals(setOf("isu", "name", "pictureUrl", "groups", "roles", "createdAt"), usersPage["items"][0].keys())
        assertEquals("MODERATOR", usersPage["items"][0]["roles"][0].stringValue())

        val user = data(get("/api/admin/users/$AUTHOR_ISU"))
        assertEquals(
            setOf("user", "roles", "groups", "createdAt", "devices", "friendsCount", "linksCount", "restrictions", "lastSeen"),
            user.keys(),
        )
        assertEquals(USER_KEYS, user["user"].keys())
        assertEquals(setOf("name", "lastLogin"), user["devices"][0].keys())
        assertEquals("2026-09-24T09:00:00Z", user["lastSeen"].stringValue())

        assertEquals("MODERATOR", data(put("/api/admin/users/$AUTHOR_ISU/roles/MODERATOR"))[0].stringValue())
        assertTrue(data(delete("/api/admin/users/$AUTHOR_ISU/roles/MODERATOR")).isArray)

        val dashboard = data(get("/api/admin/dashboard"))
        assertEquals(setOf("totals", "days"), dashboard.keys())
        assertEquals(
            setOf(
                "users", "newUsers7d", "activeDevices7d", "activeDevices30d", "webSessions7d", "friendships", "links",
                "openCases", "activeAutoSignEntries", "activeFreeSignEntries",
            ),
            dashboard["totals"].keys(),
        )
        assertEquals(setOf("PRIVATE", "PENDING", "PUBLISHED", "REJECTED", "HIDDEN"), dashboard["totals"]["links"].keys())
        assertEquals(30, dashboard["days"].size())
        assertEquals(setOf("date", "newUsers", "activeDevices", "createdLinks"), dashboard["days"][0].keys())
        assertEquals("2026-09-24", dashboard["days"][29]["date"].stringValue())

        val sport = data(get("/api/admin/system/sport"))
        assertEquals(
            setOf(
                "runs",
                "outcomes7d",
                "errors7d",
                "averageDurationMillis7d",
                "lastSuccessAt",
                "activeAutoSignEntries",
                "activeFreeSignEntries",
            ),
            sport.keys(),
        )
        assertEquals(
            setOf(
                "id", "timestamp", "outcome", "durationMillis", "receivedLessons", "newLessonsAdded", "updatedLessons",
                "skippedLessons", "errorCategory",
            ),
            sport["runs"][0].keys(),
        )
        assertEquals(setOf("SUCCESS", "PARTIAL", "FAILED"), sport["outcomes7d"].keys())
        assertEquals(SportUpdateErrorCategory.entries.map { it.name }.toSet(), sport["errors7d"].keys())
        assertEquals(1200, sport["averageDurationMillis7d"].asInt())

        val version = data(get("/api/admin/system/app-version"))
        assertEquals(setOf("latest", "minimum", "note", "overridden", "updatedAt"), version.keys())
        assertTrue(data(put("/api/admin/system/app-version").content("""{"latest":"9.3","minimum":"2.1","note":"Новое"}""")).has("latest"))
        assertEquals(version.keys(), data(get("/api/admin/system/app-version?platform=IOS")).keys())
        val iosVersion = data(put("/api/admin/system/app-version?platform=IOS").content("""{"latest":"9.4","minimum":"2.3"}"""))
        assertEquals(version.keys(), iosVersion.keys())
        val savedKeys = mockingDetails(appSettings).invocations.filter { it.method.name == "saveAll" }
            .map { call -> (call.arguments[0] as Iterable<*>).map { (it as AppSettingEntity).key } }
        assertEquals(listOf(AppVersionSettings.ANDROID_KEYS.all, AppVersionSettings.IOS_KEYS.all), savedKeys)
        for (request in listOf(get("/api/admin/system/app-version?platform=WINDOWS"), put("/api/admin/system/app-version?platform=ios"))) {
            val call = request.contentType(MediaType.APPLICATION_JSON).content("""{"latest":"9.5","minimum":"2.3"}""")
            mvc.perform(call.with(user(admin.id.toString())))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.error.code").value("invalid_request"))
        }

        val auditPage = data(get("/api/admin/audit"))
        assertEquals(PAGE_KEYS, auditPage.keys())
        assertEquals(setOf("id", "action", "target", "details", "createdAt", "actorIsu", "actorName"), auditPage["items"][0].keys())
        assertEquals(admin.isu, auditPage["items"][0]["actorIsu"].asInt())

        val reviewsSyncView = data(get("/api/admin/reviews/sync"))
        assertEquals(REVIEWS_SYNC_KEYS, reviewsSyncView.keys())
        assertEquals("FAILED", reviewsSyncView["lastOutcome"].stringValue())
        assertEquals("HTTP 503 /teacher/100123", reviewsSyncView["lastError"].stringValue())
        assertEquals(REVIEWS_SYNC_KEYS, data(post("/api/admin/reviews/sync")).keys())
        verify(reviewsSync).startManual(admin.id)

        val verification = data(get("/api/admin/reviews/verification"))
        assertEquals(setOf("pending", "verified", "unverified"), verification.keys())
        assertEquals(listOf(3L, 5L, 1L), listOf("pending", "verified", "unverified").map { verification[it].asLong() })

        val credentials = data(get("/api/admin/system/credentials"))
        assertEquals(ServiceCredential.entries.map { it.name }, credentials.values().map { it["key"].stringValue() })
        assertEquals(CREDENTIAL_KEYS, credentials[0].keys())
        val replaced = data(put("/api/admin/system/credentials/ISU_KEYCLOAK_IDENTITY").content(CREDENTIAL_REQUEST))
        assertEquals(CREDENTIAL_KEYS, replaced[0].keys())
        assertEquals("ADMIN", replaced[3]["updatedSource"].stringValue())
        assertEquals(admin.isu, replaced[3]["updatedByIsu"].asInt())

        val aiSummaries = data(get("/api/admin/reviews/summaries"))
        assertEquals(AI_SUMMARIES_KEYS, aiSummaries.keys())
        assertEquals("gemini-test-model", aiSummaries["model"].stringValue())
        assertEquals("OK", aiSummaries["keyStatus"].stringValue())
        assertEquals(listOf(3L, 0L, 1L, 0L), listOf("ready", "pending", "failed", "hidden").map { aiSummaries[it].asLong() })
        assertEquals("2026-09-24", aiSummaries["budgetDay"].stringValue())
        assertEquals(listOf(3, 400), listOf(aiSummaries["budgetUsed"].asInt(), aiSummaries["dailyBudget"].asInt()))
        assertEquals(AI_SUMMARIES_KEYS, data(post("/api/admin/reviews/summaries/run")).keys())
        verify(summaryService).startManual(admin.id)
        val summaryPage = data(get("/api/admin/reviews/summaries/teachers?status=FAILED"))
        assertEquals(PAGE_KEYS, summaryPage.keys())
        assertEquals(TEACHER_SUMMARY_KEYS, summaryPage["items"][0].keys())
        assertEquals("FAILED", summaryPage["items"][0]["status"].stringValue())
        assertEquals("Синтетический преподаватель", summaryPage["items"][0]["teacherName"].stringValue())
        verify(summaryRows).findAdminPage("FAILED", PageRequest.of(0, 20))
        assertEquals(
            TEACHER_SUMMARY_KEYS,
            data(put("/api/admin/reviews/summaries/$TEACHER_ISU/hidden").content("""{"hidden":true}""")).keys(),
        )
        verify(summaryRows).setHidden(TEACHER_ISU, NOW, admin.id, NOW)
        assertEquals(TEACHER_SUMMARY_KEYS, data(post("/api/admin/reviews/summaries/$TEACHER_ISU/regenerate")).keys())
        verify(summaryRows).request(TEACHER_ISU, NOW)
        verify(summaryService).requestTeacher(TEACHER_ISU)

        // Mutations run last: the approval resolves the shared case fixture.
        mvc.perform(post("/api/admin/moderation/restrictions/${restriction.id}/revoke").with(user(admin.id.toString())))
            .andExpect(status().isOk)
        assertEquals(
            "RESOLVED",
            data(post("/api/admin/moderation/cases/${case.id}/decisions").content("""{"action":"APPROVE"}"""))["status"].stringValue(),
        )
    }

    @Test
    fun `the web session cookie authenticates admin reads and mutations need the web header`() {
        mvc.perform(get("/api/admin/dashboard").cookie(COOKIE)).andExpect(status().isOk)
        val update = put("/api/admin/system/app-version").contentType(MediaType.APPLICATION_JSON)
            .content("""{"latest":"2.3","minimum":"2.1"}""").cookie(COOKIE)
        mvc.perform(update).andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("csrf"))
        verifyNoInteractions(appSettings)
        mvc.perform(update.header("X-Web-Request", "1")).andExpect(status().isOk).andExpect(jsonPath("$.data.latest").exists())

        val start = post("/api/admin/reviews/sync").cookie(COOKIE)
        mvc.perform(start).andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("csrf"))
        // The mocked service is an application listener, so only the start itself is checked.
        verify(reviewsSync, never()).startManual(any(UUID::class.java) ?: admin.id)
        mvc.perform(start.header("X-Web-Request", "1")).andExpect(status().isOk).andExpect(jsonPath("$.data.running").exists())
    }

    @Test
    fun `a web session older than the admin max age must sign in again on every admin route before services`() {
        `when`(webSessions.resolve(STALE_SESSION)).thenReturn(ActiveWebSession(admin.id, freshForAdmin = false))
        clearInvocations(roles)
        (moderationRoutes() + adminRoutes()).forEach {
            mvc.perform(it.contentType(MediaType.APPLICATION_JSON).cookie(Cookie("iw_session", STALE_SESSION)).header("X-Web-Request", "1"))
                .andExpect(status().isUnauthorized).andExpect(jsonPath("$.error.code").value("reauth_required"))
        }
        verifyNoInteractions(
            roles, users, cases, decisions, reports, restrictions, moderationSettings, devices, friendships, links,
            sessions, autoSign, freeSign, sportLogs, appSettings, audit, reviews, reviewStates, credentialRows, ownReviews, summaryRows,
            summaryStates,
        )
        // The same admin with a fresh session reaches every route.
        (moderationRoutes() + adminRoutes()).forEach {
            mvc.perform(it.contentType(MediaType.APPLICATION_JSON).cookie(COOKIE).header("X-Web-Request", "1"))
                .andExpect(status().isOk).andExpect(jsonPath("$.success").value(true))
        }
    }

    @Test
    fun `credential responses never carry a value and a cookie replacement needs the web header`() {
        for (request in listOf(
            get("/api/admin/system/credentials"),
            put("/api/admin/system/credentials/ISU_KEYCLOAK_IDENTITY").content(CREDENTIAL_REQUEST),
        )) {
            val body = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(user(admin.id.toString())))
                .andExpect(status().isOk).andReturn().response.contentAsString
            assertFalse(json.readTree(body)["data"].any { it.has("value") }, body)
            assertFalse(body.contains(STORED_VALUE), body)
            assertFalse(body.contains(REQUEST_VALUE), body)
        }

        val replace = put("/api/admin/system/credentials/ISU_KEYCLOAK_IDENTITY").contentType(MediaType.APPLICATION_JSON)
            .content(CREDENTIAL_REQUEST).cookie(COOKIE)
        clearInvocations(credentialRows, audit)
        mvc.perform(replace).andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("csrf"))
        verifyNoInteractions(credentialRows, audit)
        mvc.perform(replace.header("X-Web-Request", "1")).andExpect(status().isOk)
    }

    @Test
    fun `unknown and refresh-issued credential keys and unreadable bodies are rejected without echoing values`() {
        for ((request, code) in listOf(
            put("/api/admin/system/credentials/UNKNOWN_KEY").content(CREDENTIAL_REQUEST) to "invalid_request",
            put("/api/admin/system/credentials/MY_ITMO_ACCESS_TOKEN").content(CREDENTIAL_REQUEST) to "invalid_request_data",
            put("/api/admin/system/credentials/ISU_KEYCLOAK_IDENTITY").content("{\"value\":\"$REQUEST_VALUE") to "invalid_request",
        )) {
            val body = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(user(admin.id.toString())))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.error.code").value(code))
                .andReturn().response.contentAsString
            assertFalse(body.contains(REQUEST_VALUE), body)
        }
        verify(credentialRows, never()).lockAll(anyCollection())
        verifyNoInteractions(audit)
    }

    @Test
    fun `summary mutations of a web session need the web header and a non boolean hidden is unreadable`() {
        for (request in listOf(
            post("/api/admin/reviews/summaries/run"),
            put("/api/admin/reviews/summaries/$TEACHER_ISU/hidden").content("""{"hidden":true}"""),
            post("/api/admin/reviews/summaries/$TEACHER_ISU/regenerate"),
        )) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).cookie(COOKIE))
                .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("csrf"))
        }
        verify(summaryService, never()).startManual(any(UUID::class.java) ?: admin.id)
        verify(summaryService, never()).requestTeacher(anyInt())
        verifyNoInteractions(summaryRows, audit)

        for (body in listOf("""{"hidden":"true"}""", """{"hidden":1}""", """{}""")) {
            mvc.perform(
                put("/api/admin/reviews/summaries/$TEACHER_ISU/hidden").with(user(admin.id.toString()))
                    .contentType(MediaType.APPLICATION_JSON).content(body),
            )
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.error.code").value("invalid_request"))
        }
        mvc.perform(get("/api/admin/reviews/summaries/teachers?status=UNKNOWN").with(user(admin.id.toString())))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error.code").value("invalid_request"))
        verifyNoInteractions(summaryRows, audit)

        mvc.perform(post("/api/admin/reviews/summaries/run").cookie(COOKIE).header("X-Web-Request", "1"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.running").exists())
    }

    @Test
    fun `a disabled or running reviews sync answers conflict`() {
        doThrow(BusinessRuleException("Reviews sync is already running")).`when`(reviewsSync).startManual(admin.id)
        mvc.perform(post("/api/admin/reviews/sync").with(user(admin.id.toString())))
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("business_rule_violation"))
    }

    @Test
    fun `the admin role is not managed by the API and page bounds are validated`() {
        for (request in listOf(
            put("/api/admin/users/$AUTHOR_ISU/roles/ADMIN"),
            delete("/api/admin/users/$AUTHOR_ISU/roles/ADMIN"),
            get("/api/admin/users?size=101"),
            get("/api/admin/audit?page=-1"),
            get("/api/admin/moderation/cases?size=0"),
        )) {
            mvc.perform(request.with(user(admin.id.toString()))).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("invalid_request_data"))
        }
        mvc.perform(get("/api/admin/moderation/cases?reason=0").with(user(admin.id.toString()))).andExpect(status().isBadRequest)
        verify(roles, never()).grant(any(UUID::class.java) ?: admin.id, anyString(), any(Instant::class.java) ?: NOW)
        verify(roles, never()).revoke(any(UUID::class.java) ?: admin.id, any(UserRole::class.java) ?: UserRole.ADMIN)
        verifyNoInteractions(audit)
    }

    private fun data(request: MockHttpServletRequestBuilder): JsonNode {
        val body = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(user(admin.id.toString())))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return json.readTree(body)["data"]
    }

    private fun JsonNode.keys(): Set<String> = propertyNames().toSet()

    private fun labelCount(label: String, total: Long) = object : LabelCount {
        override val label = label
        override val total = total
    }

    private companion object {
        const val AUTHOR_ISU = 970001
        const val TEACHER_ISU = 970500
        val AI_SUMMARIES_KEYS = setOf(
            "enabled", "running", "runningSince", "model", "keyStatus", "lastStartedAt", "lastFinishedAt",
            "lastTrigger", "lastOutcome", "lastError", "lastGenerated", "lastFailed", "lastRequests", "ready", "pending", "failed",
            "hidden", "budgetDay", "budgetUsed", "dailyBudget",
        )
        val TEACHER_SUMMARY_KEYS = setOf(
            "teacherIsu", "teacherName", "status", "inputCount", "reviewCount", "summary", "hidden",
            "hiddenAt", "hiddenByName", "attempts", "lastAttemptAt", "lastError",
        )
        const val SESSION = "synthetic-admin-session"
        const val STALE_SESSION = "synthetic-stale-admin-session"
        val COOKIE = Cookie("iw_session", SESSION)
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
        val PAGE: Pageable = PageRequest.of(0, 20)
        val SAMPLE = UserData(0, "", null, emptyList(), UserCapabilities(false, false, false))
        val PAGE_KEYS = setOf("items", "page", "size", "total")
        val USER_KEYS = setOf("isu", "name", "pictureUrl", "groups")
        val REVIEWS_SYNC_KEYS = setOf(
            "enabled", "running", "runningSince", "lastCheckedAt", "lastChangedAt", "lastSuccessAt",
            "lastOutcome", "lastError", "lastAdded", "lastUpdated", "lastRemoved", "upstreamTeachers", "upstreamReviews",
            "reviewsTotal", "reviewsActive", "reviewsRemoved", "teachersActive",
        )
        val REVISION_KEYS = setOf(
            "id", "linkId", "number", "category", "url", "title", "visibility", "flowId", "status",
            "submittedAt", "decidedAt", "note",
        )
        const val STORED_VALUE = "synthetic-stored-credential"
        const val REQUEST_VALUE = "synthetic-request-cookie-value"
        const val CREDENTIAL_REQUEST = """{"value":"$REQUEST_VALUE"}"""
        val CREDENTIAL_KEYS = setOf(
            "key", "kind", "replaceable", "present", "status", "expiresAt", "expiresSoon", "lastUsedAt",
            "lastRenewedAt", "lastErrorAt", "lastError", "updatedAt", "updatedSource", "updatedByIsu", "updatedByName",
        )

        @Suppress("ktlint:standard:max-line-length")
        const val POLICY = """{"policies":{"SUBJECT_RESOURCE":{"premoderation":true,"reportThreshold":3,"voteThreshold":-3,"dailySubmissionLimit":5,"dailyReportLimit":10},"TEACHER_REVIEW":{"premoderation":true,"reportThreshold":3,"voteThreshold":-3,"dailySubmissionLimit":20,"dailyReportLimit":10}}}"""
    }
}
