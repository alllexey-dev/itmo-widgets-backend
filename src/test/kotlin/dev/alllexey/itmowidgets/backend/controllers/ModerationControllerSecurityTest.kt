package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.configs.*
import dev.alllexey.itmowidgets.backend.dto.*
import dev.alllexey.itmowidgets.backend.model.*
import dev.alllexey.itmowidgets.backend.repositories.*
import dev.alllexey.itmowidgets.backend.services.*
import jakarta.servlet.FilterChain
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.any
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(ModerationController::class, UserController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class, ModeratorAccess::class, ModerationService::class,
    RestrictionService::class, ModerationSettingsService::class, ModerationControllerSecurityTest.TimeConfig::class)
class ModerationControllerSecurityTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: com.fasterxml.jackson.databind.ObjectMapper,
) {
    @MockitoBean private lateinit var jwt: JwtAuthFilter
    @MockitoBean private lateinit var roles: UserRoleRepository
    @MockitoBean private lateinit var cases: ModerationCaseRepository
    @MockitoBean private lateinit var decisions: ModerationDecisionRepository
    @MockitoBean private lateinit var users: UserRepository
    @MockitoBean private lateinit var restrictions: UserRestrictionRepository
    @MockitoBean private lateinit var settings: ModerationSettingRepository
    @MockitoBean private lateinit var targets: ModerationTargets
    @MockitoBean private lateinit var userService: UserService
    @MockitoBean private lateinit var privacy: UserPrivacyService
    @MockitoBean private lateinit var profiles: UserProfileService
    @MockitoBean private lateinit var currentGroups: CurrentStudyGroupsService
    private val moderator = UUID.randomUUID()
    private val id = UUID.randomUUID()
    private val policy = """{"policies":{"SUBJECT_RESOURCE":{"premoderation":true,"reportThreshold":3,"voteThreshold":-3,"dailySubmissionLimit":5,"dailyReportLimit":10}}}"""

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig { @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-09-22T09:00:00Z"), ZoneOffset.UTC) }

    @BeforeEach
    fun fixture() {
        doAnswer { it.getArgument<FilterChain>(2).doFilter(it.getArgument(0), it.getArgument(1)); null }.`when`(jwt).doFilter(any(), any(), any())
    }

    private fun com.fasterxml.jackson.databind.JsonNode.keys(): Set<String> = fieldNames().asSequence().toSet()

    private fun routes() = listOf(get("/api/moderation/cases"),
        post("/api/moderation/cases/$id/decisions").content("""{"action":"APPROVE"}"""),
        get("/api/moderation/restrictions?isu=970001"), post("/api/moderation/restrictions/$id/revoke"),
        get("/api/moderation/settings"), put("/api/moderation/settings").content(policy))

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
    fun `moderator reads queue and settings and can update policy while own restrictions need no role`() {
        `when`(roles.existsByUserIdAndRole(moderator, UserRole.MODERATOR)).thenReturn(true)
        mvc.perform(get("/api/moderation/cases").with(user(moderator.toString())))
            .andExpect(status().isOk).andExpect(jsonPath("$.data").isEmpty)
        mvc.perform(get("/api/moderation/settings").with(user(moderator.toString())))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.policies.SUBJECT_RESOURCE.premoderation").value(true))
        mvc.perform(put("/api/moderation/settings").contentType(MediaType.APPLICATION_JSON).content(policy).with(user(moderator.toString())))
            .andExpect(status().isOk)
        clearInvocations(roles)
        mvc.perform(get("/api/users/me/restrictions").with(user(UUID.randomUUID().toString())))
            .andExpect(status().isOk).andExpect(jsonPath("$.data").isEmpty)
        verifyNoInteractions(roles)
    }

    @Test
    fun `moderator can decide inspect restrictions and revoke while capabilities stay service-authorized`() {
        `when`(roles.existsByUserIdAndRole(moderator, UserRole.MODERATOR)).thenReturn(true)
        val actor = ModerationFixture.user(970005).apply { this.id = moderator }
        val target = FakeModerationTarget()
        val case = ModerationFixture.case()
        `when`(users.findById(moderator)).thenReturn(java.util.Optional.of(actor))
        `when`(users.findByIsu(target.owner.isu)).thenReturn(target.owner)
        `when`(cases.lockById(case.id)).thenReturn(case.id)
        `when`(cases.findById(case.id)).thenReturn(java.util.Optional.of(case))
        `when`(targets.forType(case.targetType)).thenReturn(target)
        doAnswer { it.getArgument<ModerationDecisionEntity>(0) }.`when`(decisions).save(any())
        val body = mvc.perform(post("/api/moderation/cases/${case.id}/decisions").with(user(moderator.toString()))
            .contentType(MediaType.APPLICATION_JSON).content("""{"action":"APPROVE"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.status").value("RESOLVED"))
            .andReturn().response.contentAsString
        val described = json.readTree(body)["data"]["target"]
        assertEquals(setOf("targetType", "revision", "link", "author", "reports", "submitterHistory"), described.keys())
        assertEquals("SUBJECT_RESOURCE", described["targetType"].textValue())
        assertEquals(setOf("id", "linkId", "number", "category", "url", "title", "visibility", "status", "submittedAt",
            "decidedAt", "note"), described["revision"].keys())
        assertEquals(case.targetId.toString(), described["revision"]["id"].textValue())
        assertEquals(setOf("approved", "rejected", "dismissedReports", "activeRestrictions"), described["submitterHistory"].keys())
        assertEquals(setOf("isu", "name", "pictureUrl", "groups", "capabilities"), described["author"].keys())
        assertTrue(described["link"].has("isMine"))
        mvc.perform(get("/api/moderation/restrictions?isu=${target.owner.isu}").with(user(moderator.toString())))
            .andExpect(status().isOk)
        val decision = ModerationDecisionEntity(case = case, moderator = actor, action = ModerationAction.RESTRICT_USER,
            restrictionCapability = RestrictionCapability.ALL, createdAt = ModerationFixture.now)
        val restriction = UserRestrictionEntity(user = target.owner, capability = RestrictionCapability.ALL,
            decision = decision, reason = "Правила", startsAt = ModerationFixture.now)
        `when`(restrictions.findById(restriction.id)).thenReturn(java.util.Optional.of(restriction))
        mvc.perform(post("/api/moderation/restrictions/${restriction.id}/revoke").with(user(moderator.toString())))
            .andExpect(status().isOk)
        verify(restrictions).save(restriction)
    }

    @Test
    fun `numeric and unknown action values cannot become approval`() {
        for (action in listOf("0", "\"UNKNOWN\"")) {
            mvc.perform(post("/api/moderation/cases/$id/decisions").with(user(moderator.toString()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":$action}"))
                .andExpect(status().isBadRequest)
        }
        verifyNoInteractions(roles, decisions, cases)
    }
}
