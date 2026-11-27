package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkCategory
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkVisibility
import dev.alllexey.itmowidgets.backend.feature.links.persistence.SubjectLinkRevisionRepository
import dev.alllexey.itmowidgets.backend.feature.links.service.FlowOptions
import dev.alllexey.itmowidgets.backend.feature.links.service.FlowReaders
import dev.alllexey.itmowidgets.backend.feature.links.service.SubjectLinkService
import dev.alllexey.itmowidgets.backend.feature.links.service.SubjectLinkViews
import dev.alllexey.itmowidgets.backend.feature.links.web.SaveSubjectLinkRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationOutcome
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ReportReason
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationCaseRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationNoticeRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCase
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationDecisionRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationNoticeLink
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationNoticeReview
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationReportRequest
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * The author's notices on real PostgreSQL with both real targets: which decisions write one, when it becomes due, what
 * the bell lists and what a claim hands to the push, and that a revert takes the notice back.
 */
@Import(
    SubjectLinkService::class, SubjectLinkViews::class, FlowReaders::class, FlowOptions::class, ScheduleFlowMembership::class,
    UserPrivacyService::class,
    TeacherReviewService::class, TeacherReviewViews::class, TeacherSummaryViews::class, RestrictionService::class,
    ModerationSettingsService::class, ModerationService::class, ModerationReportService::class, ModerationTargets::class,
    ModeratorAccess::class, AdminAccess::class, ModerationNoticeStore::class, ModerationNoticeTest.TimeConfig::class,
)
class ModerationNoticeTest @Autowired constructor(
    private val moderation: ModerationService,
    private val store: ModerationNoticeStore,
    private val linkService: SubjectLinkService,
    private val reviewService: TeacherReviewService,
    private val linkRevisions: SubjectLinkRevisionRepository,
    private val reviewRevisions: TeacherReviewRevisionRepository,
    private val cases: ModerationCaseRepository,
    private val notices: ModerationNoticeRepository,
    private val clock: MutableClock,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var friends: FriendService

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ModerationConfig::class)
    class TimeConfig {
        @Bean fun clock() = MutableClock()

        @Bean fun objectMapper() = jacksonMapperBuilder().build()
    }

    private val users = UserSequence(em, firstIsu = 967300, createdAt = START)
    private lateinit var moderator: User
    private lateinit var admin: User

    @BeforeEach
    fun people() {
        clock.now = START
        moderator = users.next()
        admin = users.next()
        em.persist(UserRoleEntity(UserRoleId(moderator.id, UserRole.MODERATOR), START))
        em.persistAndFlush(UserRoleEntity(UserRoleId(admin.id, UserRole.ADMIN), START))
    }

    @Test
    fun `a rejected link is told with its note once the revert window has passed`() {
        val author = users.next()
        val link = submitLink(author, "Ведомость")
        val decision = decide(
            openCase(ModerationTargetType.SUBJECT_RESOURCE, linkRevision(link)),
            ModerationAction.REJECT,
            "  Не по предмету ",
        )
            .decisions.last()

        val row = notices.findById(decision.id).orElseThrow()
        assertEquals(author.id, row.recipientId)
        assertEquals(ModerationOutcome.REJECTED, row.outcome)
        assertEquals(START.plus(WINDOW).plus(ModerationService.NOTICE_GRACE), row.dueAt)

        assertTrue(recent(author).isEmpty(), "Nothing before the revert window has passed")
        assertTrue(claim().isEmpty())

        clock.now = row.dueAt
        val notice = recent(author).single()
        assertEquals(decision.id, notice.id)
        assertEquals(ModerationOutcome.REJECTED, notice.outcome)
        assertEquals("Не по предмету", notice.note)
        assertEquals(START, notice.decidedAt)
        assertEquals(ModerationNoticeLink(link, SUBJECT, "Предмет", PERIOD, "Ведомость", "example.org"), notice.link)
        assertNull(notice.review)
        assertTrue(recent(moderator).isEmpty(), "Only the author's own notices")

        val pushed = claim().single()
        assertEquals(author.id, pushed.recipientId)
        assertEquals(notice, pushed.notice)
        assertEquals(clock.now, notices.findById(decision.id).orElseThrow().sentAt)
        assertTrue(claim().isEmpty(), "A notice is pushed once")
        assertEquals(listOf(notice), recent(author), "The bell keeps a sent notice")
    }

    @Test
    fun `an approved review names the review and never carries the approval note`() {
        val author = users.next()
        val review = submitReview(author)
        val decision = decide(openCase(ModerationTargetType.TEACHER_REVIEW, reviewRevision(review)), ModerationAction.APPROVE, "Проверено")
            .decisions.last()
        clock.now = START.plus(Duration.ofHours(1))

        val notice = recent(author).single()
        assertEquals(decision.id, notice.id)
        assertEquals(ModerationOutcome.APPROVED, notice.outcome)
        assertNull(notice.note)
        assertEquals(ModerationNoticeReview(review, TEACHER, null, null), notice.review)
        assertNull(notice.link)
    }

    @Test
    fun `a revert inside the window takes the notice back`() {
        val author = users.next()
        val link = submitLink(author)
        val case = openCase(ModerationTargetType.SUBJECT_RESOURCE, linkRevision(link))
        val decision = decide(case, ModerationAction.REJECT, "Ошибка").decisions.last()
        clock.now = START.plus(WINDOW)

        moderation.revert(moderator.id, case, decision.id).also { flushed() }

        assertFalse(notices.existsById(decision.id))
        clock.now = START.plus(Duration.ofHours(1))
        assertTrue(recent(author).isEmpty())
        assertTrue(claim().isEmpty())
    }

    @Test
    fun `hiding and restoring are told, keeping a report and dismissing are not`() {
        val author = users.next()
        val link = published(author)
        repeat(3) { linkService.report(users.next().id, link, ModerationReportRequest(ReportReason.BROKEN)) }
        flushed()
        val reported = openCase(ModerationTargetType.SUBJECT_RESOURCE, linkRevision(link))

        val hide = decide(reported, ModerationAction.HIDE, "Внутренняя заметка").decisions.last()
        later()
        val restore = decide(reported, ModerationAction.RESTORE).decisions.last()
        later()

        val hidden = notices.findById(hide.id).orElseThrow()
        assertEquals(ModerationOutcome.HIDDEN, hidden.outcome)
        assertNull(hidden.note, "Only a rejection carries the note")
        assertEquals(ModerationOutcome.RESTORED, notices.findById(restore.id).orElseThrow().outcome)

        // Another round of reports: a kept report (APPROVE of an approved revision) and a DISMISS change nothing.
        repeat(3) { linkService.report(users.next().id, link, ModerationReportRequest(ReportReason.BROKEN)) }
        flushed()
        val kept = decide(openCase(ModerationTargetType.SUBJECT_RESOURCE, linkRevision(link)), ModerationAction.APPROVE).decisions.last()
        later()
        repeat(3) { linkService.report(users.next().id, link, ModerationReportRequest(ReportReason.BROKEN)) }
        flushed()
        val dismissed = decide(openCase(ModerationTargetType.SUBJECT_RESOURCE, linkRevision(link)), ModerationAction.DISMISS)
            .decisions.last()

        assertFalse(notices.existsById(kept.id))
        assertFalse(notices.existsById(dismissed.id))
    }

    @Test
    fun `an admin deciding about their own content is told nothing`() {
        val link = submitLink(admin)
        val decision = decide(openCase(ModerationTargetType.SUBJECT_RESOURCE, linkRevision(link)), ModerationAction.APPROVE, caller = admin)
            .decisions.last()
        assertFalse(notices.existsById(decision.id))
    }

    @Test
    fun `a stale notice is marked without a push and a deleted link is left out`() {
        val author = users.next()
        val stale = submitLink(author)
        val gone = submitLink(author)
        decide(openCase(ModerationTargetType.SUBJECT_RESOURCE, linkRevision(stale)), ModerationAction.REJECT)
        decide(openCase(ModerationTargetType.SUBJECT_RESOURCE, linkRevision(gone)), ModerationAction.REJECT)
        linkService.delete(author.id, gone)
        flushed()
        clock.now = START.plus(Duration.ofDays(4))

        val claimed = store.claimDue(clock.now, clock.now.minus(Duration.ofDays(3)), 10)

        assertEquals(2, claimed.count)
        assertTrue(claimed.pushes.isEmpty())
        assertEquals(listOf(stale), recent(author).map { it.link?.id })
        assertTrue(store.claimDue(clock.now, clock.now.minus(Duration.ofDays(3)), 10).pushes.isEmpty())

        assertEquals(2, store.purge(clock.now))
        assertTrue(recent(author).isEmpty())
    }

    private fun recent(user: User) = store.recent(user.id, clock.now.minus(Duration.ofDays(30)), clock.now, 50)

    private fun claim() = store.claimDue(clock.now, clock.now.minus(Duration.ofDays(3)), 10).pushes.also { flushed() }

    private fun submitLink(author: User, title: String? = null): UUID = UUID.randomUUID().also { id ->
        linkService.save(
            author.id,
            id,
            SaveSubjectLinkRequest(
                SUBJECT,
                "Предмет",
                PERIOD,
                LinkCategory.MATERIALS,
                "https://example.org/$id",
                title,
                LinkVisibility.ALL,
                null,
            ),
        )
        flushed()
    }

    private fun published(author: User): UUID {
        val link = submitLink(author)
        decide(openCase(ModerationTargetType.SUBJECT_RESOURCE, linkRevision(link)), ModerationAction.APPROVE)
        later()
        return link
    }

    private fun submitReview(author: User): UUID =
        reviewService.save(author.id, TEACHER, SaveTeacherReviewRequest(null, TEXT, true, emptyList())).mine!!.id.also { flushed() }

    private fun linkRevision(id: UUID) = linkRevisions.findAllByLink(id).maxBy { it.number }.id

    private fun reviewRevision(id: UUID) = reviewRevisions.findAllByReview(id).maxBy { it.number }.id

    private fun openCase(type: ModerationTargetType, revision: UUID): UUID = cases.findOpen(type, revision)!!.id

    private fun decide(case: UUID, action: ModerationAction, note: String? = null, caller: User = moderator): ModerationCase =
        moderation.decide(caller.id, case, ModerationDecisionRequest(action, note)).also { flushed() }

    private fun later() {
        clock.now = clock.now.plusSeconds(60)
    }

    private fun flushed() {
        em.flush()
        em.clear()
    }

    private companion object {
        val START: Instant = Instant.parse("2026-09-24T09:00:00Z")
        val WINDOW: Duration = Duration.ofMinutes(10)
        const val SUBJECT = 42L
        const val PERIOD = "2026-1"
        const val TEACHER = 142415
        const val TEXT = "Объясняет понятно, на вопросы отвечает подробно."
    }
}
