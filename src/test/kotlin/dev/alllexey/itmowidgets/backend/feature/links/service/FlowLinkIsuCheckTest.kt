package dev.alllexey.itmowidgets.backend.feature.links.service

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminUserSummaries
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialStore
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkCategory
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkVisibility
import dev.alllexey.itmowidgets.backend.feature.links.web.SaveSubjectLinkRequest
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationPolicy
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationReportService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationSettingsService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationTargets
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModeratorAccess
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationDecisionRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationSettings
import dev.alllexey.itmowidgets.backend.feature.reviews.service.IsuConfig
import dev.alllexey.itmowidgets.backend.feature.reviews.service.IsuErrorCategory
import dev.alllexey.itmowidgets.backend.feature.reviews.service.IsuPotokCache
import dev.alllexey.itmowidgets.backend.feature.reviews.service.IsuVerificationService
import dev.alllexey.itmowidgets.backend.feature.reviews.service.IsuVerificationServiceTest.FakeIsuClient
import dev.alllexey.itmowidgets.backend.feature.reviews.service.ReviewVerificationStore
import dev.alllexey.itmowidgets.backend.feature.schedule.service.ScheduleFlowMembership
import dev.alllexey.itmowidgets.backend.feature.social.service.FriendService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.testing.MutableClock
import dev.alllexey.itmowidgets.backend.testing.insertUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.task.TaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.*

/** The ISU check of premoderated FLOW links. Everything commits, so the class runs without a test transaction. */
@Import(
    IsuVerificationService::class, IsuPotokCache::class, ReviewVerificationStore::class, ServiceCredentialStore::class,
    AdminAuditService::class, AdminAccess::class, AdminUserSummaries::class, FlowLinkIsuQueue::class,
    SubjectLinkService::class, SubjectLinkViews::class, FlowReaders::class, ScheduleFlowMembership::class, UserPrivacyService::class,
    RestrictionService::class, ModerationSettingsService::class, ModerationService::class, ModerationReportService::class,
    ModerationTargets::class, ModeratorAccess::class, FlowLinkIsuCheckTest.TestConfig::class,
)
@TestPropertySource(properties = ["itmowidgets.isu.request-delay=0ms", "itmowidgets.isu.flow-link-max-members=3"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlowLinkIsuCheckTest @Autowired constructor(
    private val isuCheck: IsuVerificationService,
    private val links: SubjectLinkService,
    private val moderation: ModerationService,
    private val settings: ModerationSettingsService,
    private val isu: FakeIsuClient,
    private val kicks: RecordingExecutor,
    private val clock: MutableClock,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var friends: FriendService

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IsuConfig::class)
    class TestConfig {
        @Bean fun fakeIsuClient() = FakeIsuClient()

        @Bean fun isuExecutor() = RecordingExecutor()

        @Bean fun clock() = MutableClock()
    }

    /** Counts kicks without running them: the tests start each ISU run themselves. */
    class RecordingExecutor : TaskExecutor {
        @Volatile var kicks = 0

        override fun execute(task: Runnable) {
            kicks++
        }
    }

    private var nextIsu = FIRST_ISU
    private lateinit var moderator: UUID

    @BeforeEach
    fun reset() {
        cleanup()
        clock.now = NOW
        isu.reset()
        isuCheck.onIdentityReplaced()
        kicks.kicks = 0
        moderator = jdbc.insertUser(MODERATOR_ISU)
        jdbc.update("INSERT INTO user_roles (user_id, role, granted_at) VALUES (?, 'MODERATOR', now())", moderator)
    }

    @AfterEach
    fun cleanup() {
        val revisions = """
            SELECT r.id FROM subject_link_revisions r JOIN subject_links l ON l.id = r.link_id JOIN users u ON u.id = l.owner_id
            WHERE u.isu BETWEEN $FIRST_ISU AND $MODERATOR_ISU
        """
        val users = "SELECT id FROM users WHERE isu BETWEEN $FIRST_ISU AND $MODERATOR_ISU"
        jdbc.update("DELETE FROM moderation_decisions WHERE case_id IN (SELECT id FROM moderation_cases WHERE target_id IN ($revisions))")
        jdbc.update("DELETE FROM moderation_cases WHERE target_id IN ($revisions)")
        jdbc.update("DELETE FROM moderation_settings WHERE updated_by IN ($users)")
        jdbc.update("DELETE FROM subject_links WHERE owner_id IN ($users)")
        jdbc.update("DELETE FROM user_subject_flows WHERE user_id IN ($users)")
        jdbc.update("DELETE FROM user_roles WHERE user_id IN ($users)")
        jdbc.update("DELETE FROM users WHERE isu BETWEEN ? AND ?", FIRST_ISU, MODERATOR_ISU)
        jdbc.update("DELETE FROM isu_potoks")
        jdbc.update(
            """
            UPDATE service_credentials SET value = NULL, expires_at = NULL, status = 'MISSING', last_used_at = NULL,
                last_renewed_at = NULL, last_error_at = NULL, last_error = NULL, updated_by = NULL, updated_source = NULL,
                updated_at = now()
            """,
        )
    }

    @Test
    fun `ISU approves a flow link of a member of a small flow and records it in the case`() {
        cookie()
        val author = author()
        val classmate = author()
        val id = save(author, LinkVisibility.FLOW)
        val everybody = save(author, LinkVisibility.ALL)
        assertEquals(Row("PENDING", NOW, 0), row(id))
        assertEquals(Row("PENDING", null, 0), row(everybody), "Only FLOW links are checked")
        assertEquals(1, kicks.kicks)
        isu.members[FLOW] = setOf(author.isu, classmate.isu, OUTSIDER)

        isuCheck.run()

        assertEquals(Row("APPROVED", null, 0), row(id))
        assertEquals(listOf("login", "members/$FLOW"), isu.calls)
        assertEquals(listOf(Decision("POLICY", "APPROVE", "ISU check: the author is in flow $FLOW of 3 members")), decisions(id))
        assertEquals("RESOLVED", caseStatus(id))
        assertEquals(listOf(id), links.links(classmate.id, SUBJECT, PERIOD).shared.map { it.id })
        assertEquals(Row("PENDING", null, 0), row(everybody))
    }

    @Test
    fun `a flow larger than the limit or without the author leaves the link to moderators`() {
        cookie()
        val inLarge = author()
        val outsider = author()
        val large = save(inLarge, LinkVisibility.FLOW)
        val foreign = save(outsider, LinkVisibility.FLOW, flowId = SMALL_FLOW)
        isu.members[FLOW] = setOf(inLarge.isu, OUTSIDER, OUTSIDER + 1, OUTSIDER + 2)
        isu.members[SMALL_FLOW] = setOf(OUTSIDER)

        isuCheck.run()

        for (id in listOf(large, foreign)) {
            assertEquals(Row("PENDING", null, 0), row(id))
            assertEquals("OPEN", caseStatus(id))
            assertEquals(emptyList(), decisions(id))
        }
        isu.calls.clear()
        isuCheck.run()
        assertEquals(emptyList(), isu.calls, "A finished check is not repeated")
    }

    @Test
    fun `without ISU the link waits and a later successful check approves it`() {
        val author = author()
        val id = save(author, LinkVisibility.FLOW)
        isu.members[FLOW] = setOf(author.isu)

        isuCheck.run()
        assertEquals(Row("PENDING", NOW.plus(Duration.ofHours(6)), 0), row(id))
        assertEquals(emptyList(), isu.calls)

        cookie()
        clock.now = NOW.plus(Duration.ofHours(6))
        isu.failOnce["members/$FLOW"] = IsuErrorCategory.NETWORK
        isuCheck.run()
        assertEquals(Row("PENDING", clock.now.plus(Duration.ofMinutes(30)), 1), row(id))
        assertEquals("OPEN", caseStatus(id))

        clock.now = clock.now.plus(Duration.ofMinutes(30))
        isuCheck.run()
        assertEquals(Row("APPROVED", null, 1), row(id))
        assertEquals("RESOLVED", caseStatus(id))
    }

    @Test
    fun `a moderator's decision made during the check wins over ISU`() {
        cookie()
        val author = author()
        val id = save(author, LinkVisibility.FLOW)
        isu.members[FLOW] = setOf(author.isu)
        isu.beforeRequest = { decide(id, ModerationAction.REJECT) }

        isuCheck.run()

        assertEquals("REJECTED", row(id).status)
        assertEquals(listOf(Decision("MODERATOR", "REJECT", null)), decisions(id))
        assertEquals("RESOLVED", caseStatus(id))
    }

    @Test
    fun `without premoderation a flow link is published at once and never reaches ISU`() {
        cookie()
        premoderation(false)
        val author = author()

        val saved = links.save(author.id, UUID.randomUUID(), request(LinkVisibility.FLOW, FLOW))
        isuCheck.run()

        assertEquals(SubjectLinkStatus.PUBLISHED, saved.status)
        assertEquals(Row("APPROVED", null, 0), row(saved.id))
        assertEquals(0, kicks.kicks)
        assertEquals(emptyList(), isu.calls)
    }

    private data class Row(val status: String, val dueAt: Instant?, val attempts: Int)

    private data class Decision(val actor: String, val action: String, val note: String?)

    private data class Author(val id: UUID, val isu: Int)

    private fun row(linkId: UUID): Row = jdbc.queryForObject(
        """
        SELECT status, isu_check_due_at, isu_check_attempts FROM subject_link_revisions
        WHERE link_id = ? ORDER BY number DESC LIMIT 1
        """,
        { rs, _ -> Row(rs.getString(1), rs.getObject(2, OffsetDateTime::class.java)?.toInstant(), rs.getInt(3)) },
        linkId,
    )!!

    private fun decisions(linkId: UUID): List<Decision> = jdbc.query(
        """
        SELECT d.actor, d.action, d.note FROM moderation_decisions d JOIN moderation_cases c ON c.id = d.case_id
        JOIN subject_link_revisions r ON r.id = c.target_id WHERE r.link_id = ? ORDER BY d.created_at
        """,
        { rs, _ -> Decision(rs.getString(1), rs.getString(2), rs.getString(3)) },
        linkId,
    )

    private fun caseStatus(linkId: UUID): String = jdbc.queryForObject(
        "SELECT c.status FROM moderation_cases c JOIN subject_link_revisions r ON r.id = c.target_id WHERE r.link_id = ?",
        String::class.java,
        linkId,
    )!!

    private fun author(): Author {
        val isu = nextIsu++
        val id = jdbc.insertUser(isu)
        for (flow in listOf(FLOW, SMALL_FLOW)) {
            jdbc.update(
                """
                INSERT INTO user_subject_flows (user_id, subject_id, period_key, flow_id, group_name, type_id, last_seen)
                VALUES (?, ?, ?, ?, 'M3100', 3, '2026-09-07')
                """,
                id,
                SUBJECT,
                PERIOD,
                flow,
            )
        }
        return Author(id, isu)
    }

    private fun save(author: Author, visibility: LinkVisibility, flowId: Long = FLOW): UUID =
        links.save(author.id, UUID.randomUUID(), request(visibility, flowId.takeIf { visibility == LinkVisibility.FLOW })).id

    private fun request(visibility: LinkVisibility, flowId: Long?) = SaveSubjectLinkRequest(
        SUBJECT,
        "Предмет",
        PERIOD,
        LinkCategory.MATERIALS,
        "https://example.org/${UUID.randomUUID()}",
        null,
        visibility,
        flowId,
    )

    private fun decide(linkId: UUID, action: ModerationAction) {
        val case = jdbc.queryForObject(
            "SELECT c.id FROM moderation_cases c JOIN subject_link_revisions r ON r.id = c.target_id WHERE r.link_id = ? AND c.status = 'OPEN'",
            UUID::class.java,
            linkId,
        )!!
        moderation.decide(moderator, case, ModerationDecisionRequest(action))
    }

    private fun premoderation(enabled: Boolean) = settings.update(
        moderator,
        ModerationSettings(
            mapOf(
                ModerationTargetType.SUBJECT_RESOURCE to ModerationPolicy(premoderation = enabled),
                ModerationTargetType.TEACHER_REVIEW to ModerationPolicy(),
            ),
        ),
    )

    private fun cookie() = jdbc.update(
        """
        UPDATE service_credentials SET value = 'synthetic-cookie', status = 'OK', updated_source = 'SEED', updated_at = now()
        WHERE key = 'ISU_KEYCLOAK_IDENTITY'
        """,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
        const val FIRST_ISU = 967100
        const val MODERATOR_ISU = 967199
        const val OUTSIDER = 967300
        const val SUBJECT = 42L
        const val PERIOD = "2026-1"
        const val FLOW = 93724L
        const val SMALL_FLOW = 93725L
    }
}
