package dev.alllexey.itmowidgets.backend.feature.moderation.web

import dev.alllexey.itmowidgets.backend.feature.admin.model.AdminAuditEntity
import dev.alllexey.itmowidgets.backend.feature.admin.persistence.AdminAuditRepository
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminModerationService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminRestrictionViews
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminUserSummaries
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.moderation.model.UserRestrictionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationCaseRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationDecisionRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationReportRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationSettingRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.UserRestrictionRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.service.FakeModerationTarget
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationFixture
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationSettingsService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationTargets
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModeratorAccess
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherNamesService
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRoleRepository
import dev.alllexey.itmowidgets.backend.feature.users.service.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserProfileService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.users.web.UserController
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebLoginService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.platform.error.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.platform.security.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import dev.alllexey.itmowidgets.backend.testing.TestUsers
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@WebMvcTest(ModerationController::class, UserController::class)
@Import(
    SecurityConfig::class, GlobalExceptionHandler::class, ModeratorAccess::class, AdminAccess::class, ModerationService::class,
    RestrictionService::class, ModerationSettingsService::class, AdminModerationService::class, AdminUserSummaries::class,
    AdminRestrictionViews::class, AdminAuditService::class, ModerationControllerSecurityTest.TimeConfig::class,
)
class ModerationControllerSecurityTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: tools.jackson.databind.ObjectMapper,
) {
    @MockitoBean private lateinit var jwt: JwtAuthFilter

    @MockitoBean private lateinit var webSessions: WebSessionService

    @MockitoBean private lateinit var webLogins: WebLoginService

    @MockitoBean private lateinit var roles: UserRoleRepository

    @MockitoBean private lateinit var cases: ModerationCaseRepository

    @MockitoBean private lateinit var decisions: ModerationDecisionRepository

    @MockitoBean private lateinit var users: UserRepository

    @MockitoBean private lateinit var restrictions: UserRestrictionRepository

    @MockitoBean private lateinit var settings: ModerationSettingRepository

    @MockitoBean private lateinit var targets: ModerationTargets

    @MockitoBean private lateinit var teacherNames: TeacherNamesService

    @MockitoBean private lateinit var userService: UserService

    @MockitoBean private lateinit var privacy: UserPrivacyService

    @MockitoBean private lateinit var profiles: UserProfileService

    @MockitoBean private lateinit var currentGroups: CurrentStudyGroupsService

    @MockitoBean private lateinit var reports: ModerationReportRepository

    @MockitoBean private lateinit var audit: AdminAuditRepository
    private val moderator = UUID.randomUUID()
    private val id = UUID.randomUUID()

    @Suppress("ktlint:standard:max-line-length")
    private val policy = """{"policies":{"SUBJECT_RESOURCE":{"premoderation":true,"reportThreshold":3,"voteThreshold":-3,"dailySubmissionLimit":5,"dailyReportLimit":10},"TEACHER_REVIEW":{"premoderation":true,"reportThreshold":3,"voteThreshold":-3,"dailySubmissionLimit":20,"dailyReportLimit":10}}}"""

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-09-22T09:00:00Z"), ZoneOffset.UTC)
    }

    @BeforeEach
    fun fixture() {
        doAnswer {
            it.getArgument<FilterChain>(2).doFilter(it.getArgument(0), it.getArgument(1))
            null
        }.`when`(jwt).doFilter(any(), any(), any())
    }

    private fun tools.jackson.databind.JsonNode.keys(): Set<String> = propertyNames().toSet()

    private fun routes() = listOf(
        get("/api/moderation/cases"),
        post("/api/moderation/cases/$id/decisions").content("""{"action":"APPROVE"}"""),
        get("/api/moderation/restrictions?isu=970001"),
        post("/api/moderation/restrictions/$id/revoke"),
        get("/api/moderation/settings"),
        put("/api/moderation/settings").content(policy),
    )

    @Test
    fun `all routes reject anonymous callers before services`() {
        (routes() + get("/api/users/me/restrictions")).forEach {
            mvc.perform(it.contentType(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden)
        }
        verifyNoInteractions(roles, cases, decisions, users, restrictions, settings, targets)
    }

    @Test
    fun `authenticated non moderator is denied every moderator route without persistence access`() {
        routes().forEach {
            mvc.perform(it.contentType(MediaType.APPLICATION_JSON).with(user(moderator.toString())))
                .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("permission_denied"))
        }
        verifyNoInteractions(cases, decisions, users, restrictions, settings, targets)
    }

    @Test
    fun `moderator reads queue and settings but only an admin changes the policy while own restrictions need no role`() {
        `when`(roles.existsByUserIdAndRole(moderator, UserRole.MODERATOR)).thenReturn(true)
        mvc.perform(get("/api/moderation/cases").with(user(moderator.toString())))
            .andExpect(status().isOk).andExpect(jsonPath("$.data").isEmpty)
        mvc.perform(get("/api/moderation/settings").with(user(moderator.toString())))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.policies.SUBJECT_RESOURCE.premoderation").value(true))
        mvc.perform(
            put("/api/moderation/settings").contentType(MediaType.APPLICATION_JSON).content(policy).with(user(moderator.toString())),
        )
            .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("permission_denied"))
        verify(settings, never()).upsert(
            anyString(),
            anyString(),
            any(Instant::class.java) ?: Instant.EPOCH,
            any(UUID::class.java) ?: moderator,
        )
        verifyNoInteractions(audit)

        val admin = UUID.randomUUID()
        `when`(roles.existsByUserIdAndRole(admin, UserRole.ADMIN)).thenReturn(true)
        mvc.perform(put("/api/moderation/settings").contentType(MediaType.APPLICATION_JSON).content(policy).with(user(admin.toString())))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.policies.SUBJECT_RESOURCE.dailySubmissionLimit").value(5))
        verify(settings).upsert("SUBJECT_RESOURCE.daily_submission_limit", "5", ModerationFixture.now, admin)
        val recorded = org.mockito.ArgumentCaptor.forClass(AdminAuditEntity::class.java)
        verify(audit).save(recorded.capture())
        assertEquals("MODERATION_SETTINGS_CHANGED", recorded.value.action)
        assertEquals("SUBJECT_RESOURCE.daily_submission_limit 20 -> 5", recorded.value.details)
        assertEquals(admin, recorded.value.actorId)

        clearInvocations(roles)
        mvc.perform(get("/api/users/me/restrictions").with(user(UUID.randomUUID().toString())))
            .andExpect(status().isOk).andExpect(jsonPath("$.data").isEmpty)
        verifyNoInteractions(roles)
    }

    @Test
    fun `moderator can decide inspect restrictions and revoke while capabilities stay service-authorized`() {
        `when`(roles.existsByUserIdAndRole(moderator, UserRole.MODERATOR)).thenReturn(true)
        val actor = TestUsers.user(970005, createdAt = ModerationFixture.now).apply { this.id = moderator }
        val target = FakeModerationTarget()
        val case = ModerationFixture.case()
        `when`(users.findById(moderator)).thenReturn(java.util.Optional.of(actor))
        `when`(users.findByIsu(target.owner.isu)).thenReturn(target.owner)
        `when`(cases.lockById(case.id)).thenReturn(case.id)
        `when`(cases.findById(case.id)).thenReturn(java.util.Optional.of(case))
        `when`(targets.forType(case.targetType)).thenReturn(target)
        doAnswer { it.getArgument<ModerationDecisionEntity>(0) }.`when`(decisions).save(any())
        val body = mvc.perform(
            post("/api/moderation/cases/${case.id}/decisions").with(user(moderator.toString()))
                .contentType(MediaType.APPLICATION_JSON).content("""{"action":"APPROVE"}"""),
        )
            .andExpect(status().isOk).andExpect(jsonPath("$.data.status").value("RESOLVED"))
            .andReturn().response.contentAsString
        val described = json.readTree(body)["data"]["target"]
        assertEquals(setOf("targetType", "revision", "link", "author", "reports", "submitterHistory"), described.keys())
        assertEquals("SUBJECT_RESOURCE", described["targetType"].stringValue())
        assertEquals(
            setOf(
                "id", "linkId", "number", "category", "url", "title", "visibility", "flowId", "status",
                "submittedAt", "decidedAt", "note",
            ),
            described["revision"].keys(),
        )
        assertEquals(case.targetId.toString(), described["revision"]["id"].stringValue())
        assertEquals(setOf("approved", "rejected", "dismissedReports", "activeRestrictions"), described["submitterHistory"].keys())
        assertEquals(setOf("isu", "name", "pictureUrl", "groups", "capabilities"), described["author"].keys())
        assertTrue(described["link"].has("isMine"))
        mvc.perform(get("/api/moderation/restrictions?isu=${target.owner.isu}").with(user(moderator.toString())))
            .andExpect(status().isOk)
        val decision = ModerationDecisionEntity(
            case = case,
            moderator = actor,
            action = ModerationAction.RESTRICT_USER,
            restrictionCapability = RestrictionCapability.ALL,
            createdAt = ModerationFixture.now,
        )
        val restriction = UserRestrictionEntity(
            user = target.owner,
            capability = RestrictionCapability.ALL,
            decision = decision,
            reason = "Правила",
            startsAt = ModerationFixture.now,
        )
        `when`(restrictions.findById(restriction.id)).thenReturn(java.util.Optional.of(restriction))
        mvc.perform(post("/api/moderation/restrictions/${restriction.id}/revoke").with(user(moderator.toString())))
            .andExpect(status().isOk)
        verify(restrictions).save(restriction)
    }

    @Test
    fun `numeric and unknown action values cannot become approval`() {
        for (action in listOf("0", "\"UNKNOWN\"")) {
            mvc.perform(
                post("/api/moderation/cases/$id/decisions").with(user(moderator.toString()))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"action\":$action}"),
            )
                .andExpect(status().isBadRequest)
        }
        verifyNoInteractions(roles, decisions, cases)
    }
}
