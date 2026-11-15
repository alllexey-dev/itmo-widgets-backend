package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkCategory
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkVisibility
import dev.alllexey.itmowidgets.backend.feature.links.persistence.SubjectLinkRevisionRepository
import dev.alllexey.itmowidgets.backend.feature.links.service.FlowReaders
import dev.alllexey.itmowidgets.backend.feature.links.service.SubjectLinkService
import dev.alllexey.itmowidgets.backend.feature.links.service.SubjectLinkViews
import dev.alllexey.itmowidgets.backend.feature.links.web.SaveSubjectLinkRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationActor
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationPolicy
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ReportReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationCaseRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationDecisionRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCase
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationDecisionRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationReportRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationSettings
import dev.alllexey.itmowidgets.backend.feature.moderation.web.RestrictionRequest
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewRevisionRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherReviewService
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherReviewViews
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherSummaryViews
import dev.alllexey.itmowidgets.backend.feature.reviews.web.SaveTeacherReviewRequest
import dev.alllexey.itmowidgets.backend.feature.schedule.service.ScheduleFlowMembership
import dev.alllexey.itmowidgets.backend.feature.social.service.FriendService
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleId
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.testing.MutableClock
import dev.alllexey.itmowidgets.backend.testing.UserSequence
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * Reverting decisions on real PostgreSQL with both real targets: every revertible action on links and reviews
 * leaves the target, its revisions, cases and reports exactly as before the decision, and every refusal changes
 * nothing.
 */
@Import(
    SubjectLinkService::class, SubjectLinkViews::class, FlowReaders::class, ScheduleFlowMembership::class, UserPrivacyService::class,
    TeacherReviewService::class, TeacherReviewViews::class, TeacherSummaryViews::class, RestrictionService::class,
    ModerationSettingsService::class, ModerationService::class, ModerationReportService::class, ModerationTargets::class,
    ModeratorAccess::class, AdminAccess::class, ModerationRevertTest.TimeConfig::class,
)
class ModerationRevertTest @Autowired constructor(
    private val moderation: ModerationService,
    private val settings: ModerationSettingsService,
    private val linkService: SubjectLinkService,
    private val reviewService: TeacherReviewService,
    private val linkRevisions: SubjectLinkRevisionRepository,
    private val reviewRevisions: TeacherReviewRevisionRepository,
    private val cases: ModerationCaseRepository,
    private val decisions: ModerationDecisionRepository,
    private val clock: MutableClock,
    private val jdbc: JdbcTemplate,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var friends: FriendService

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ModerationConfig::class)
    class TimeConfig {
        @Bean fun clock() = MutableClock()

        @Bean fun objectMapper() = jacksonMapperBuilder().build()
    }

    private val users = UserSequence(em, firstIsu = 967100, createdAt = START)
    private lateinit var moderator: User
    private lateinit var admin: User

    /** A kind of target: how to submit, publish, report, edit and delete one, and its rows. */
    private interface Kind {
        val type: ModerationTargetType

        /** A new submission of [author] with its open SUBMISSION case; the id of the link or review. */
        fun submit(author: User): UUID
        fun edit(author: User, id: UUID)
        fun delete(author: User, id: UUID)
        fun report(reporter: User, id: UUID)

        /** The newest revision of the link or review. */
        fun revision(id: UUID): UUID

        /** The link or review row, its revisions, their cases and reports, as the database has them. */
        fun rows(id: UUID): String
    }

    private val links = object : Kind {
        override val type = ModerationTargetType.SUBJECT_RESOURCE
        override fun submit(author: User) = UUID.randomUUID().also { save(author, it, "https://example.org/$it") }
        override fun edit(author: User, id: UUID) = save(author, id, "https://example.org/$id/edited").let { }
        override fun delete(author: User, id: UUID) = linkService.delete(author.id, id)
        override fun report(reporter: User, id: UUID) =
            linkService.report(reporter.id, id, ModerationReportRequest(ReportReason.BROKEN)).let { }
        override fun revision(id: UUID) = linkRevisions.findAllByLink(id).maxBy { it.number }.id
        override fun rows(id: UUID) = rows("subject_links", "subject_link_revisions", "link_id", id)

        private fun save(author: User, id: UUID, url: String) = linkService.save(
            author.id,
            id,
            SaveSubjectLinkRequest(SUBJECT, "Предмет", PERIOD, LinkCategory.MATERIALS, url, null, LinkVisibility.ALL, null),
        )
    }

    private val reviews = object : Kind {
        override val type = ModerationTargetType.TEACHER_REVIEW
        override fun submit(author: User) = save(author, TEXT)
        override fun edit(author: User, id: UUID) = save(author, OTHER_TEXT).let { }
        override fun delete(author: User, id: UUID) = reviewService.delete(author.id, TEACHER).let { }
        override fun report(reporter: User, id: UUID) =
            reviewService.report(reporter.id, id, ModerationReportRequest(ReportReason.SPAM)).let { }
        override fun revision(id: UUID) = reviewRevisions.findAllByReview(id).maxBy { it.number }.id
        override fun rows(id: UUID) = rows("teacher_reviews", "teacher_review_revisions", "review_id", id)

        private fun save(author: User, text: String) =
            reviewService.save(author.id, TEACHER, SaveTeacherReviewRequest(null, text, true, emptyList())).mine!!.id
    }

    @BeforeEach
    fun people() {
        clock.now = START
        moderator = users.next()
        admin = users.next()
        em.persist(UserRoleEntity(UserRoleId(moderator.id, UserRole.MODERATOR), START))
        em.persistAndFlush(UserRoleEntity(UserRoleId(admin.id, UserRole.ADMIN), START))
    }

    // region every revertible action leaves the rows exactly as before

    @Test
    fun `approving and rejecting a submission are undone exactly on links and reviews`() {
        for (kind in listOf(links, reviews)) {
            for ((action, note) in listOf(ModerationAction.APPROVE to null, ModerationAction.REJECT to "Не по предмету")) {
                val id = kind.submit(users.next())
                val case = openCase(kind, id)
                roundTrip(kind, id, case, action, note)
                assertEquals(ModerationCaseReason.SUBMISSION, cases.findById(case).orElseThrow().reason)
            }
        }
    }

    @Test
    fun `rejecting, hiding and dismissing on a reports case are undone exactly, the dismissed reports included`() {
        for (kind in listOf(links, reviews)) {
            for (action in listOf(ModerationAction.REJECT, ModerationAction.HIDE, ModerationAction.DISMISS)) {
                val id = reported(kind)
                roundTrip(kind, id, openCase(kind, id), action, "Синтетическая причина")
            }
        }
    }

    @Test
    fun `a restore appended to a resolved case is undone and the case stays resolved as it was`() {
        for (kind in listOf(links, reviews)) {
            val id = reported(kind)
            val case = openCase(kind, id)
            decide(case, ModerationAction.HIDE)
            later()
            val resolvedAt = cases.findById(case).orElseThrow().resolvedAt
            assertNotNull(resolvedAt)

            roundTrip(kind, id, case, ModerationAction.RESTORE)

            val row = cases.findById(case).orElseThrow()
            assertEquals(ModerationCaseStatus.RESOLVED, row.status)
            assertEquals(resolvedAt, row.resolvedAt)
        }
    }

    @Test
    fun `a reverted case returns to its place in the queue and keeps both steps`() {
        val older = links.submit(users.next())
        later()
        val newer = links.submit(users.next())
        clock.now = START
        val case = openCase(links, older)
        decide(case, ModerationAction.APPROVE)
        assertEquals(listOf(openCase(links, newer)), cases.findAllByStatusOrderByOpenedAt(ModerationCaseStatus.OPEN).map { it.id })
        later()

        val reverted = revert(case)

        assertEquals(ModerationCaseStatus.OPEN, reverted.status)
        assertEquals(START, reverted.openedAt)
        assertEquals(listOf(case, openCase(links, newer)), cases.findAllByStatusOrderByOpenedAt(ModerationCaseStatus.OPEN).map { it.id })
        val decision = reverted.decisions.single()
        assertEquals(ModerationAction.APPROVE, decision.action)
        assertEquals(clock.now, decision.revertedAt)
        assertNull(decision.revertibleUntil)
        assertEquals(
            mapOf("reverted_by" to moderator.id, "reverted_at" to Timestamp.from(clock.now)),
            jdbc.queryForMap("SELECT reverted_by, reverted_at FROM moderation_decision_reverts WHERE decision_id = ?", decision.id),
        )
        // The case takes a new decision as before.
        assertEquals(ModerationCaseStatus.RESOLVED, decide(case, ModerationAction.REJECT).status)
    }

    // endregion

    // region refusals change nothing

    @Test
    fun `the window closes after ten minutes`() {
        for (kind in listOf(links, reviews)) {
            val id = kind.submit(users.next())
            val case = openCase(kind, id)
            val decided = decide(case, ModerationAction.APPROVE).decisions.last()
            assertEquals(clock.now.plus(WINDOW), decided.revertibleUntil)

            clock.now = decided.createdAt.plus(WINDOW).plusSeconds(1)
            refused<BusinessRuleException>(kind, id, case, "The revert window has passed")
            assertNull(moderation.case(moderator.id, case).decisions.last().revertibleUntil)

            clock.now = decided.createdAt.plus(WINDOW)
            assertEquals(ModerationCaseStatus.OPEN, revert(case).status, "The window's last instant still counts")
        }
    }

    @Test
    fun `only the latest decision of a case is reverted, and only once`() {
        val id = reported(links)
        val case = openCase(links, id)
        val hide = decide(case, ModerationAction.HIDE).decisions.last()
        later()
        val restore = decide(case, ModerationAction.RESTORE).decisions.last()
        later()

        refused<BusinessRuleException>(links, id, case, "Only the latest decision of a case can be reverted", hide.id)

        revert(case, restore.id)
        refused<BusinessRuleException>(links, id, case, "The decision is already reverted", restore.id)
        refused<BusinessRuleException>(links, id, case, "Only the latest decision of a case can be reverted", hide.id)
    }

    @Test
    fun `restrictions, author-wide hiding and policy decisions are not reverted`() {
        val author = users.next()
        val id = links.submit(author)
        val case = openCase(links, id)
        val restrict = decide(
            case,
            ModerationAction.RESTRICT_USER,
            restriction = RestrictionRequest(RestrictionCapability.SUBMIT_RESOURCES, 1),
        ).decisions.last()
        refused<BusinessRuleException>(links, id, case, NOT_REVERTIBLE, restrict.id)
        val hideAll = decide(case, ModerationAction.HIDE_ALL_BY_USER).decisions.last()
        refused<BusinessRuleException>(links, id, case, NOT_REVERTIBLE, hideAll.id)

        premoderation(false)
        val automatic = links.submit(users.next())
        val policyCase = cases.findAll().single { it.targetId == links.revision(automatic) }
        val policy = moderation.case(admin.id, policyCase.id).decisions.single()
        assertEquals(ModerationActor.POLICY, policy.actor)
        assertNull(policy.revertibleUntil)
        for (caller in listOf(moderator, admin)) {
            refused<BusinessRuleException>(links, automatic, policyCase.id, NOT_REVERTIBLE, policy.id, caller)
        }
    }

    @Test
    fun `a decision taken before reverts existed has no snapshot and is refused`() {
        val id = reported(links)
        val case = openCase(links, id)
        val old = em.persistAndFlush(
            ModerationDecisionEntity(
                case = cases.findById(case).orElseThrow(),
                moderator = moderator,
                action = ModerationAction.DISMISS,
                createdAt = clock.now,
            ),
        )
        jdbc.update("UPDATE moderation_cases SET status = 'RESOLVED', resolved_at = ? WHERE id = ?", Timestamp.from(clock.now), case)
        em.clear()

        refused<BusinessRuleException>(links, id, case, "The decision was taken before decisions could be reverted", old.id)
    }

    @Test
    fun `an edit or a deletion by the author since the decision refuses the revert`() {
        for (kind in listOf(links, reviews)) {
            val author = users.next()
            val id = kind.submit(author)
            val case = openCase(kind, id)
            decide(case, ModerationAction.APPROVE)
            later()
            kind.edit(author, id)
            refused<BusinessRuleException>(kind, id, case, CHANGED)

            val owner = users.next()
            val deleted = kind.submit(owner)
            val deletedCase = openCase(kind, deleted)
            decide(deletedCase, ModerationAction.REJECT)
            later()
            kind.delete(owner, deleted)
            flushed()
            val before = caseRows(deletedCase)
            val error = assertFailsWith<BusinessRuleException> { revert(deletedCase) }
            assertEquals("The target was deleted since the decision", error.message)
            assertEquals(before, caseRows(deletedCase))
        }
    }

    @Test
    fun `an edit published at once without premoderation refuses the revert of a link`() {
        val author = users.next()
        val id = links.submit(author)
        val case = openCase(links, id)
        decide(case, ModerationAction.REJECT)
        premoderation(false)
        later()
        links.edit(author, id)
        assertTrue(cases.findAllByStatusOrderByOpenedAt(ModerationCaseStatus.OPEN).none { it.targetId == links.revision(id) })

        refused<BusinessRuleException>(links, id, case, CHANGED)
    }

    @Test
    fun `another decision on the target or another open case for it refuses the revert`() {
        for (kind in listOf(links, reviews)) {
            val id = kind.submit(users.next())
            val case = openCase(kind, id)
            decide(case, ModerationAction.APPROVE)
            later()
            repeat(3) { kind.report(users.next(), id) }
            val reports = openCase(kind, id)
            assertNotEquals(case, reports)
            refused<BusinessRuleException>(kind, id, case, "Another case for the target is open")

            decide(reports, ModerationAction.HIDE)
            later()
            refused<BusinessRuleException>(kind, id, case, CHANGED)
        }
    }

    // endregion

    // region who may revert

    @Test
    fun `the deciding moderator and an admin revert, another moderator or a student may not`() {
        val other = users.next()
        val student = users.next()
        em.persistAndFlush(UserRoleEntity(UserRoleId(other.id, UserRole.MODERATOR), START))
        val id = links.submit(users.next())
        val case = openCase(links, id)
        val decided = decide(case, ModerationAction.APPROVE).decisions.last()
        later()

        assertEquals(decided.createdAt.plus(WINDOW), moderation.case(moderator.id, case).decisions.last().revertibleUntil)
        assertEquals(decided.createdAt.plus(WINDOW), moderation.case(admin.id, case).decisions.last().revertibleUntil)
        assertNull(moderation.case(other.id, case).decisions.last().revertibleUntil)
        refused<PermissionDeniedException>(links, id, case, "Only the deciding moderator or an admin can revert a decision", caller = other)
        refused<PermissionDeniedException>(links, id, case, "Moderator role required", caller = student)

        assertEquals(ModerationCaseStatus.OPEN, revert(case, caller = admin).status)
        assertEquals(
            admin.id,
            jdbc.queryForObject("SELECT reverted_by FROM moderation_decision_reverts WHERE decision_id = ?", UUID::class.java, decided.id),
        )
    }

    @Test
    fun `a moderator never reverts a decision about their own content, even one they took as an admin`() {
        val owner = users.next()
        em.persistAndFlush(UserRoleEntity(UserRoleId(owner.id, UserRole.ADMIN), START))
        val id = links.submit(owner)
        val case = openCase(links, id)
        decide(case, ModerationAction.APPROVE, caller = owner)
        later()
        em.remove(em.find(UserRoleEntity::class.java, UserRoleId(owner.id, UserRole.ADMIN))!!)
        em.persistAndFlush(UserRoleEntity(UserRoleId(owner.id, UserRole.MODERATOR), START))
        em.clear()

        assertNull(moderation.case(owner.id, case).decisions.last().revertibleUntil)
        refused<PermissionDeniedException>(links, id, case, "Moderators cannot decide cases about their own content", caller = owner)

        // An admin's decision about a moderator's content is the admin's to revert, not the moderator's.
        val other = links.submit(owner)
        val otherCase = openCase(links, other)
        decide(otherCase, ModerationAction.REJECT, caller = admin)
        later()
        refused<PermissionDeniedException>(
            links,
            other,
            otherCase,
            "Only the deciding moderator or an admin can revert a decision",
            caller = owner,
        )
        assertEquals(ModerationCaseStatus.OPEN, revert(otherCase, caller = admin).status)
    }

    // endregion

    /** Decides, reverts a minute later and checks that every row of the target is as before the decision. */
    private fun roundTrip(kind: Kind, id: UUID, case: UUID, action: ModerationAction, note: String? = null) {
        val before = kind.rows(id)
        val taken = decide(case, action, note).decisions.last()
        assertEquals(action, taken.action)
        assertNotEquals(before, kind.rows(id), "$action on ${kind.type} changed nothing")
        later()

        val reverted = revert(case, taken.id)

        assertEquals(before, kind.rows(id), "$action on ${kind.type}")
        assertEquals(clock.now, reverted.decisions.single { it.id == taken.id }.revertedAt)
    }

    /** A published link or review with three reports and an open REPORTS case on its revision. */
    private fun reported(kind: Kind): UUID {
        val id = kind.submit(users.next())
        decide(openCase(kind, id), ModerationAction.APPROVE)
        later()
        repeat(3) { kind.report(users.next(), id) }
        assertEquals(ModerationCaseReason.REPORTS, cases.findOpen(kind.type, kind.revision(id))?.reason)
        return id
    }

    private inline fun <reified T : Throwable> refused(
        kind: Kind,
        id: UUID,
        case: UUID,
        message: String,
        decision: UUID? = null,
        caller: User = moderator,
    ) {
        val before = kind.rows(id) + caseRows(case)
        val error = assertFailsWith<T> { revert(case, decision, caller) }
        assertEquals(message, error.message)
        em.clear()
        assertEquals(before, kind.rows(id) + caseRows(case), "Nothing is half-undone")
    }

    private fun decide(
        case: UUID,
        action: ModerationAction,
        note: String? = null,
        restriction: RestrictionRequest? = null,
        caller: User = moderator,
    ): ModerationCase = moderation.decide(caller.id, case, ModerationDecisionRequest(action, note, restriction)).also { flushed() }

    /** Reverts [decision], by default the case's latest one. */
    private fun revert(case: UUID, decision: UUID? = null, caller: User = moderator): ModerationCase {
        val target = decision ?: decisions.findAllByCaseIdOrderByCreatedAtAscIdAsc(case).last().id
        return moderation.revert(caller.id, case, target).also { flushed() }
    }

    private fun openCase(kind: Kind, id: UUID): UUID = cases.findOpen(kind.type, kind.revision(id))!!.id

    private fun later() {
        clock.now = clock.now.plusSeconds(60)
    }

    private fun premoderation(enabled: Boolean) {
        settings.update(
            admin.id,
            ModerationSettings(
                mapOf(
                    ModerationTargetType.SUBJECT_RESOURCE to ModerationPolicy(premoderation = enabled),
                    ModerationTargetType.TEACHER_REVIEW to ModerationPolicy(),
                ),
            ),
        )
        flushed()
    }

    private fun flushed() {
        em.flush()
        em.clear()
    }

    private fun caseRows(case: UUID): String = listOf(
        jdbc.queryForList("SELECT * FROM moderation_cases WHERE id = ?", case),
        jdbc.queryForList("SELECT * FROM moderation_decisions WHERE case_id = ? ORDER BY created_at, id", case),
        jdbc.queryForList(
            "SELECT r.* FROM moderation_decision_reverts r JOIN moderation_decisions d ON d.id = r.decision_id WHERE d.case_id = ?",
            case,
        ),
    ).toString()

    private fun rows(table: String, revisions: String, parent: String, id: UUID): String {
        em.flush()
        val targets = "SELECT id FROM $revisions WHERE $parent = ?"
        return listOf(
            jdbc.queryForList("SELECT * FROM $table WHERE id = ?", id),
            jdbc.queryForList("SELECT * FROM $revisions WHERE $parent = ? ORDER BY number", id),
            jdbc.queryForList("SELECT * FROM moderation_cases WHERE target_id IN ($targets) ORDER BY opened_at, id", id),
            jdbc.queryForList("SELECT * FROM moderation_reports WHERE target_id IN ($targets) ORDER BY id", id),
        ).toString()
    }

    private companion object {
        val START: Instant = Instant.parse("2026-09-24T09:00:00Z")
        val WINDOW: Duration = Duration.ofMinutes(10)
        const val SUBJECT = 42L
        const val PERIOD = "2026-1"
        const val TEACHER = 142415
        const val TEXT = "Объясняет понятно, на вопросы отвечает подробно."
        const val OTHER_TEXT = "Строгий, но справедливый; лабораторные принимает вовремя."
        const val NOT_REVERTIBLE = "Only an APPROVE, REJECT, HIDE, RESTORE or DISMISS decision of a moderator can be reverted"
        const val CHANGED = "The target changed since the decision"
    }
}
