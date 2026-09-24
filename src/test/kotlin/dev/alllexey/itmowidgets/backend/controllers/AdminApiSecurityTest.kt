package dev.alllexey.itmowidgets.backend.controllers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.alllexey.itmowidgets.backend.configs.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.configs.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.configs.SecurityConfig
import dev.alllexey.itmowidgets.backend.dto.*
import dev.alllexey.itmowidgets.backend.model.*
import dev.alllexey.itmowidgets.backend.repositories.*
import dev.alllexey.itmowidgets.backend.services.*
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** The role matrix of every admin route with the real services and access rules; only persistence is mocked. */
@WebMvcTest(AdminModerationController::class, AdminUsersController::class, AdminDashboardController::class,
    AdminSystemController::class, AdminAuditController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class, AdminAccess::class, ModeratorAccess::class, ModerationService::class,
    RestrictionService::class, ModerationSettingsService::class, AdminModerationService::class, AdminUsersService::class,
    AdminDashboardService::class, AdminSystemService::class, AdminAuditService::class, AdminUserSummaries::class,
    AdminRestrictionViews::class, AppVersionSettings::class, AdminApiSecurityTest.TimeConfig::class)
class AdminApiSecurityTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
) {
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

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig { @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC) }

    private val admin = ModerationFixture.user(970100)
    private val moderator = ModerationFixture.user(970200)
    private val student = ModerationFixture.user(970300)
    private val author = ModerationFixture.user(AUTHOR_ISU)
    private val case = ModerationFixture.case()
    private val decision = ModerationDecisionEntity(case = case, moderator = moderator, action = ModerationAction.RESTRICT_USER,
        restrictionCapability = RestrictionCapability.VOTE, restrictionDays = 7, createdAt = NOW)
    private val restriction = UserRestrictionEntity(user = author, capability = RestrictionCapability.VOTE, decision = decision,
        reason = "Спам", startsAt = NOW, expiresAt = NOW.plusSeconds(86_400))
    private val target = object : ModerationTarget {
        override fun targetType() = ModerationTargetType.SUBJECT_RESOURCE
        override fun ownerId(targetId: UUID) = author.id
        override fun isReportable(targetId: UUID, reporterId: UUID) = true
        override fun apply(action: ModerationAction, targetId: UUID, decision: ModerationDecisionEntity) = Unit
        override fun describe(targetId: UUID, viewerId: UUID): ModerationCaseTarget = ModerationFixture.linkTarget(targetId, author)
        override fun summaries(targetIds: Collection<UUID>) = targetIds.associateWith { id ->
            CaseTargetSummary(ModerationFixture.linkTarget(id, author).revision,
                AdminLinkSummary(UUID.randomUUID(), 42, "Предмет", "2026-1", 3, hidden = false), author.id)
        }
    }

    @BeforeEach
    fun fixture() {
        doAnswer { it.getArgument<FilterChain>(2).doFilter(it.getArgument(0), it.getArgument(1)); null }.`when`(jwt).doFilter(any(), any(), any())
        `when`(roles.existsByUserIdAndRole(admin.id, UserRole.ADMIN)).thenReturn(true)
        `when`(roles.existsByUserIdAndRole(moderator.id, UserRole.MODERATOR)).thenReturn(true)
        `when`(webSessions.resolve(SESSION)).thenReturn(admin.id)
        for (actor in listOf(admin, moderator)) `when`(users.findById(actor.id)).thenReturn(Optional.of(actor))
        `when`(users.findSummaryRows(anyCollection())).thenAnswer { invocation ->
            val ids = invocation.getArgument<Collection<UUID>>(0)
            listOf(admin, moderator, author).filter { it.id in ids }.map { UserSummaryRow(it.id, it.isu, it.name, it.pictureUrl, it.createdAt) }
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

        `when`(cases.findPage(any(ModerationCaseStatus::class.java) ?: ModerationCaseStatus.OPEN,
            nullable(ModerationCaseReason::class.java), any(Pageable::class.java) ?: PAGE)).thenAnswer {
            PageImpl(listOf(case), it.getArgument(2), 1)
        }
        `when`(cases.findById(case.id)).thenReturn(Optional.of(case))
        `when`(cases.lockById(case.id)).thenReturn(case.id)
        `when`(targets.forType(ModerationTargetType.SUBJECT_RESOURCE)).thenReturn(target)
        `when`(reports.countActiveByTargets(ModerationTargetType.SUBJECT_RESOURCE, listOf(case.targetId)))
            .thenReturn(listOf(TargetCountRow(case.targetId, 2)))
        doAnswer { it.getArgument<ModerationDecisionEntity>(0) }.`when`(decisions).save(any())
        `when`(restrictions.findAdminPage(nullable(Int::class.javaObjectType), anyBoolean(), any(Instant::class.java) ?: NOW,
            any(Pageable::class.java) ?: PAGE)).thenAnswer {
            PageImpl(listOf(AdminRestrictionRow(restriction.id, author.id, restriction.capability, restriction.reason, NOW,
                restriction.expiresAt, null, null, case.id)), it.getArgument(3), 1)
        }
        `when`(restrictions.findById(restriction.id)).thenReturn(Optional.of(restriction))
        doAnswer { it.getArgument<UserData>(0) }.`when`(currentGroups).userData(any(UserData::class.java) ?: SAMPLE)

        `when`(sportLogs.findTop50ByOrderByUpdateTimestampDescIdDesc()).thenReturn(listOf(SportUpdateLog(id = 7, updateTimestamp = NOW,
            outcome = SportUpdateOutcome.FAILED, durationMillis = 1200, receivedLessons = 0, newLessonsAdded = 0, updatedLessons = 0,
            skippedLessons = 0, errorCategory = SportUpdateErrorCategory.NETWORK)))
        `when`(sportLogs.countOutcomesSince(any(Instant::class.java) ?: NOW)).thenReturn(listOf(SportOutcomeRow(SportUpdateOutcome.FAILED, 1, 1200)))
        `when`(audit.findPage(any(Pageable::class.java) ?: PAGE)).thenAnswer {
            PageImpl(listOf(AdminAuditEntity(actorId = admin.id, action = "ROLE_GRANTED", target = "user:$AUTHOR_ISU",
                details = "role MODERATOR", createdAt = NOW)), it.getArgument(0), 1)
        }
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
        get("/api/admin/audit?page=0&size=10"),
    )

    @Test
    fun `anonymous callers are denied every admin route before services`() {
        (moderationRoutes() + adminRoutes()).forEach { mvc.perform(it.contentType(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden) }
        verifyNoInteractions(roles, users, cases, decisions, reports, restrictions, moderationSettings, devices, friendships, links,
            sessions, autoSign, freeSign, sportLogs, appSettings, audit)
    }

    @Test
    fun `a regular user is denied every admin route without data access`() {
        (moderationRoutes() + adminRoutes()).forEach {
            mvc.perform(it.contentType(MediaType.APPLICATION_JSON).with(user(student.id.toString())))
                .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("permission_denied"))
        }
        verifyNoInteractions(users, cases, decisions, reports, restrictions, moderationSettings, devices, friendships, links,
            sessions, autoSign, freeSign, sportLogs, appSettings, audit)
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
        verify(moderationSettings, never()).upsert(anyString(), anyString(), any(Instant::class.java) ?: NOW, any(UUID::class.java) ?: admin.id)
        verifyNoInteractions(devices, friendships, links, sessions, sportLogs, appSettings, audit)
    }

    @Test
    fun `an admin reaches every route and the responses have exact camelCase keys`() {
        val queue = data(get("/api/admin/moderation/cases"))
        assertEquals(PAGE_KEYS, queue.keys())
        assertEquals(setOf("id", "targetType", "status", "reason", "openedAt", "resolvedAt", "revision", "link", "author", "reportCount"),
            queue["items"][0].keys())
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
        assertEquals(setOf("id", "user", "capability", "reason", "startsAt", "expiresAt", "revokedAt", "revokedByIsu", "active", "caseId"),
            restrictionPage["items"][0].keys())
        assertTrue(restrictionPage["items"][0]["active"].asBoolean())

        assertEquals(setOf("policies"), data(get("/api/admin/moderation/settings")).keys())
        assertEquals(setOf("policies"), data(put("/api/admin/moderation/settings").content(POLICY)).keys())

        val usersPage = data(get("/api/admin/users?query=P32"))
        assertEquals(PAGE_KEYS, usersPage.keys())
        assertEquals(setOf("isu", "name", "pictureUrl", "groups", "roles", "createdAt"), usersPage["items"][0].keys())
        assertEquals("MODERATOR", usersPage["items"][0]["roles"][0].textValue())

        val user = data(get("/api/admin/users/$AUTHOR_ISU"))
        assertEquals(setOf("user", "roles", "groups", "createdAt", "devices", "friendsCount", "linksCount", "restrictions", "lastSeen"), user.keys())
        assertEquals(USER_KEYS, user["user"].keys())
        assertEquals(setOf("name", "lastLogin"), user["devices"][0].keys())
        assertEquals("2026-09-24T09:00:00Z", user["lastSeen"].textValue())

        assertEquals("MODERATOR", data(put("/api/admin/users/$AUTHOR_ISU/roles/MODERATOR"))[0].textValue())
        assertTrue(data(delete("/api/admin/users/$AUTHOR_ISU/roles/MODERATOR")).isArray)

        val dashboard = data(get("/api/admin/dashboard"))
        assertEquals(setOf("totals", "days"), dashboard.keys())
        assertEquals(setOf("users", "newUsers7d", "activeDevices7d", "activeDevices30d", "webSessions7d", "friendships", "links",
            "openCases", "activeAutoSignEntries", "activeFreeSignEntries"), dashboard["totals"].keys())
        assertEquals(setOf("PRIVATE", "PENDING", "PUBLISHED", "REJECTED", "HIDDEN"), dashboard["totals"]["links"].keys())
        assertEquals(30, dashboard["days"].size())
        assertEquals(setOf("date", "newUsers", "activeDevices", "createdLinks"), dashboard["days"][0].keys())
        assertEquals("2026-09-24", dashboard["days"][29]["date"].textValue())

        val sport = data(get("/api/admin/system/sport"))
        assertEquals(setOf("runs", "outcomes7d", "errors7d", "averageDurationMillis7d", "lastSuccessAt", "activeAutoSignEntries",
            "activeFreeSignEntries"), sport.keys())
        assertEquals(setOf("id", "timestamp", "outcome", "durationMillis", "receivedLessons", "newLessonsAdded", "updatedLessons",
            "skippedLessons", "errorCategory"), sport["runs"][0].keys())
        assertEquals(setOf("SUCCESS", "PARTIAL", "FAILED"), sport["outcomes7d"].keys())
        assertEquals(SportUpdateErrorCategory.entries.map { it.name }.toSet(), sport["errors7d"].keys())
        assertEquals(1200, sport["averageDurationMillis7d"].asInt())

        val version = data(get("/api/admin/system/app-version"))
        assertEquals(setOf("latest", "minimum", "note", "overridden", "updatedAt"), version.keys())
        assertTrue(data(put("/api/admin/system/app-version").content("""{"latest":"9.3","minimum":"2.1","note":"Новое"}""")).has("latest"))
        verify(appSettings).saveAll(anyCollection())

        val auditPage = data(get("/api/admin/audit"))
        assertEquals(PAGE_KEYS, auditPage.keys())
        assertEquals(setOf("id", "action", "target", "details", "createdAt", "actorIsu", "actorName"), auditPage["items"][0].keys())
        assertEquals(admin.isu, auditPage["items"][0]["actorIsu"].asInt())

        // Mutations run last: the approval resolves the shared case fixture.
        mvc.perform(post("/api/admin/moderation/restrictions/${restriction.id}/revoke").with(user(admin.id.toString())))
            .andExpect(status().isOk)
        assertEquals("RESOLVED", data(post("/api/admin/moderation/cases/${case.id}/decisions").content("""{"action":"APPROVE"}"""))["status"].textValue())
    }

    @Test
    fun `the web session cookie authenticates admin reads and mutations need the web header`() {
        mvc.perform(get("/api/admin/dashboard").cookie(COOKIE)).andExpect(status().isOk)
        val update = put("/api/admin/system/app-version").contentType(MediaType.APPLICATION_JSON)
            .content("""{"latest":"2.3","minimum":"2.1"}""").cookie(COOKIE)
        mvc.perform(update).andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("csrf"))
        verifyNoInteractions(appSettings)
        mvc.perform(update.header("X-Web-Request", "1")).andExpect(status().isOk).andExpect(jsonPath("$.data.latest").exists())
    }

    @Test
    fun `the admin role is not managed by the API and page bounds are validated`() {
        for (request in listOf(put("/api/admin/users/$AUTHOR_ISU/roles/ADMIN"), delete("/api/admin/users/$AUTHOR_ISU/roles/ADMIN"),
            get("/api/admin/users?size=101"), get("/api/admin/audit?page=-1"), get("/api/admin/moderation/cases?size=0"))) {
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

    private fun JsonNode.keys(): Set<String> = fieldNames().asSequence().toSet()

    private companion object {
        const val AUTHOR_ISU = 970001
        const val SESSION = "synthetic-admin-session"
        val COOKIE = Cookie("iw_session", SESSION)
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
        val PAGE: Pageable = PageRequest.of(0, 20)
        val SAMPLE = UserData(0, "", null, emptyList(), UserCapabilities(false, false, false))
        val PAGE_KEYS = setOf("items", "page", "size", "total")
        val USER_KEYS = setOf("isu", "name", "pictureUrl", "groups")
        val REVISION_KEYS = setOf("id", "linkId", "number", "category", "url", "title", "visibility", "flowId", "status",
            "submittedAt", "decidedAt", "note")
        const val POLICY = """{"policies":{"SUBJECT_RESOURCE":{"premoderation":true,"reportThreshold":3,"voteThreshold":-3,"dailySubmissionLimit":5,"dailyReportLimit":10}}}"""
    }
}
