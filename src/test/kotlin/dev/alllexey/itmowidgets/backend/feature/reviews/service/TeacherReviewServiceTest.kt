package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationPolicy
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ReportReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.moderation.model.UserRestrictionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationCaseRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationReportRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationReportService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationSettingsService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationTargets
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModeratorAccess
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationDecisionRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationReportRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationSettings
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.IsuPotokEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.IsuPotokTeacherEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.IsuPotokTeacherId
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewProvider
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewVerification
import dev.alllexey.itmowidgets.backend.feature.reviews.model.StoredScale
import dev.alllexey.itmowidgets.backend.feature.reviews.model.StoredSummary
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryConfidence
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryLevel
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleKind
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleValue
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryTag
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewVoteEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewVoteId
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherSummaryEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewFlowRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewRevisionRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.web.SaveTeacherReviewRequest
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReviewKind
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReviewStatus
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReviewsResponse
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherSummary
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherSummaryLevel
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherSummaryScale
import dev.alllexey.itmowidgets.backend.feature.schedule.model.LessonEntity
import dev.alllexey.itmowidgets.backend.feature.social.service.FriendService
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleId
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.RestrictedException
import dev.alllexey.itmowidgets.backend.testing.UserSequence
import dev.alllexey.itmowidgets.backend.testing.persistUser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.*

@Import(
    TeacherReviewService::class, TeacherReviewViews::class, TeacherSummaryViews::class, UserPrivacyService::class,
    RestrictionService::class,
    ModerationSettingsService::class, ModerationService::class, ModerationReportService::class, ModerationTargets::class,
    ModeratorAccess::class, AdminAccess::class, TeacherReviewServiceTest.TimeConfig::class,
)
class TeacherReviewServiceTest @Autowired constructor(
    private val service: TeacherReviewService,
    private val moderation: ModerationService,
    private val settings: ModerationSettingsService,
    private val reviewRows: TeacherReviewRepository,
    private val revisions: TeacherReviewRevisionRepository,
    private val reviewFlows: TeacherReviewFlowRepository,
    private val cases: ModerationCaseRepository,
    private val reportRows: ModerationReportRepository,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var friends: FriendService

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneId.of("Europe/Moscow"))

        @Bean fun objectMapper() = jacksonMapperBuilder().build()
    }

    private val users = UserSequence(em, firstIsu = 965000, createdAt = NOW)
    private var nextExternalId = 1L
    private lateinit var moderator: User

    @BeforeEach
    fun moderator() {
        moderator = users.next()
        em.persistAndFlush(UserRoleEntity(UserRoleId(moderator.id, UserRole.MODERATOR), NOW))
    }

    @Test
    fun `a new review waits for a moderator and only its author sees it`() {
        val author = users.next()
        val reader = users.next()

        val response = save(author, subject = "Математика")

        val mine = assertNotNull(response.mine)
        assertEquals(TeacherReviewStatus.PENDING, mine.status)
        assertEquals("Математика", mine.subjectTitle)
        assertTrue(mine.anonymous)
        assertEquals(LocalDate.of(2026, 9, 23), mine.writtenOn, "The Moscow date of the newest revision")
        assertTrue(response.reviews.isEmpty())
        val revision = assertNotNull(revisions.findPending(mine.id))
        assertEquals(1, revision.number)
        assertEquals(ModerationCaseReason.SUBMISSION, cases.findOpen(TYPE, revision.id)?.reason)
        assertTrue(reviews(reader).reviews.isEmpty())
        assertNull(reviews(reader).mine)
        val row = reviewRows.findById(mine.id).orElseThrow()
        assertEquals(ReviewVerification.PENDING, row.verification)
        assertEquals(NOW, row.verificationDueAt)
        assertEquals(0, row.verificationAttempts)
    }

    @Test
    fun `an approved review is shown to others with the name only when written under it`() {
        val anonymousAuthor = users.next()
        val namedAuthor = users.next()
        val reader = users.next()
        val anonymous = save(anonymousAuthor).mine!!.id
        val named = save(namedAuthor, text = OTHER_TEXT, anonymous = false).mine!!.id
        decide(anonymous, ModerationAction.APPROVE)
        decide(named, ModerationAction.APPROVE)

        val response = reviews(reader)
        val shown = response.reviews.associateBy { it.id }
        assertEquals(setOf(anonymous, named), shown.keys)
        assertNull(shown.getValue(anonymous).author)
        assertEquals(namedAuthor.isu, shown.getValue(named).author?.isu)
        with(shown.getValue(anonymous)) {
            assertEquals(TeacherReviewKind.COMMUNITY, kind)
            assertEquals(TEXT, text)
            assertEquals(LocalDate.of(2026, 9, 23), writtenOn)
            assertNull(writtenBeforeYear)
            assertNull(sourceTitle)
            assertNull(sourceLink)
            assertFalse(verified)
            assertFalse(reportedByMe)
            assertEquals(0, myVote)
        }
        assertNull(response.mine)
        assertEquals(TeacherReviewStatus.PUBLISHED, reviews(anonymousAuthor).mine?.status)
        assertEquals(listOf(named), reviews(anonymousAuthor).reviews.map { it.id }, "The own review comes only in mine")
    }

    @Test
    fun `editing a published review keeps the approved text for others until a decision`() {
        val author = users.next()
        val reader = users.next()
        val id = save(author).mine!!.id
        decide(id, ModerationAction.APPROVE)

        val edited = save(author, text = OTHER_TEXT, subject = "Физика").mine!!
        assertEquals(TeacherReviewStatus.PENDING, edited.status)
        assertEquals(OTHER_TEXT, edited.text)
        assertEquals(TEXT, reviews(reader).reviews.single().text)
        assertNull(reviews(reader).reviews.single().subjectTitle)

        decide(id, ModerationAction.REJECT, note = "Не о преподавателе")
        val rejected = reviews(author).mine!!
        assertEquals(TeacherReviewStatus.REJECTED, rejected.status)
        assertEquals("Не о преподавателе", rejected.reviewNote)
        assertEquals(OTHER_TEXT, rejected.text)
        assertEquals(TEXT, reviews(reader).reviews.single().text)

        save(author, text = THIRD_TEXT)
        decide(id, ModerationAction.APPROVE)
        assertEquals(THIRD_TEXT, reviews(reader).reviews.single().text)
        assertNull(reviews(author).mine!!.reviewNote)
    }

    @Test
    fun `switching anonymity changes no content and applies at once`() {
        dailyLimit(1)
        val author = users.next()
        val reader = users.next()
        val id = save(author, anonymous = false).mine!!.id
        decide(id, ModerationAction.APPROVE)
        assertEquals(author.isu, reviews(reader).reviews.single().author?.isu)
        restrict(author, RestrictionCapability.WRITE_REVIEWS)

        val switched = save(author, anonymous = true).mine!!

        assertTrue(switched.anonymous)
        assertEquals(TeacherReviewStatus.PUBLISHED, switched.status)
        assertEquals(1, revisions.findAllByReview(id).size)
        assertNull(reviews(reader).reviews.single().author)
        assertFalse(save(author, anonymous = false).mine!!.anonymous)
    }

    @Test
    fun `a second save edits the same review and replaces its flows`() {
        val author = users.next()
        val first = save(author, flows = listOf(3, 1, 3)).mine!!.id
        assertEquals(listOf(1L, 3L), reviewFlows.findFlowIds(first))

        val second = save(author, text = OTHER_TEXT, flows = listOf(7)).mine!!.id

        assertEquals(first, second)
        assertEquals(1, reviewRows.findAllByAuthorId(author.id).size)
        assertEquals(listOf(7L), reviewFlows.findFlowIds(first))
        save(author, text = OTHER_TEXT)
        assertEquals(emptyList(), reviewFlows.findFlowIds(first))
    }

    @Test
    fun `invalid input is refused and accepted input is normalized`() {
        val author = users.next()
        val self = em.persistUser(TEACHER, createdAt = NOW)
        for (request in listOf(
            SaveTeacherReviewRequest(text = "a".repeat(29)),
            SaveTeacherReviewRequest(text = "  " + "a".repeat(29) + "  "),
            SaveTeacherReviewRequest(text = "a".repeat(3001)),
            SaveTeacherReviewRequest(text = TEXT + "\u0007"),
            SaveTeacherReviewRequest(text = TEXT + "\rпродолжение"),
            SaveTeacherReviewRequest(subjectTitle = "П".repeat(201), text = TEXT),
            SaveTeacherReviewRequest(text = TEXT, flowIds = (1L..51L).toList()),
            SaveTeacherReviewRequest(text = TEXT, flowIds = listOf(5, 0)),
        )) {
            assertFailsWith<InvalidRequestDataException>(request.text.take(40)) { service.save(author.id, TEACHER, request) }
        }
        assertFailsWith<InvalidRequestDataException> { service.save(author.id, 99_999, SaveTeacherReviewRequest(text = TEXT)) }
        assertFailsWith<InvalidRequestDataException> { service.save(self.id, TEACHER, SaveTeacherReviewRequest(text = TEXT)) }
        assertNull(reviews(author).mine)

        val normalized = service.save(
            author.id,
            TEACHER,
            SaveTeacherReviewRequest(
                subjectTitle = "   ",
                text = "  Первая строка отзыва о преподавателе\r\nВторая строка\tс табуляцией  ",
                flowIds = (1L..50L).toList(),
            ),
        ).mine!!
        assertNull(normalized.subjectTitle)
        assertEquals("Первая строка отзыва о преподавателе\nВторая строка\tс табуляцией", normalized.text)

        val emoji = "😀".repeat(30)
        assertEquals(60, emoji.length)
        val saved = service.save(author.id, TEACHER, SaveTeacherReviewRequest(subjectTitle = "П".repeat(200), text = emoji)).mine!!
        assertEquals(emoji, saved.text)
        em.flush()
        assertEquals(emoji, revisions.findPending(saved.id)!!.text)
    }

    @Test
    fun `restrictions block writing but not deleting`() {
        val author = users.next()
        save(author)
        val restricted = users.next()
        val everything = users.next()
        restrict(restricted, RestrictionCapability.WRITE_REVIEWS)
        restrict(everything, RestrictionCapability.ALL)
        restrict(author, RestrictionCapability.WRITE_REVIEWS)

        assertFailsWith<RestrictedException> { save(restricted) }
        assertFailsWith<RestrictedException> { save(everything) }
        assertFailsWith<RestrictedException> { save(author, text = OTHER_TEXT) }
        assertNull(service.delete(author.id, TEACHER).mine)
        assertTrue(reviewRows.findAllByAuthorId(author.id).isEmpty())
    }

    @Test
    fun `the daily limit counts new revisions only`() {
        dailyLimit(2)
        val author = users.next()
        save(author)
        save(author, text = OTHER_TEXT)

        assertFailsWith<BusinessRuleException> { save(author, isu = OTHER_TEACHER) }
        assertFailsWith<BusinessRuleException> { save(author, text = THIRD_TEXT) }
        assertEquals(OTHER_TEXT, save(author, text = OTHER_TEXT, anonymous = false).mine!!.text)
    }

    @Test
    fun `votes replace and remove each other on own reviews and copies`() {
        val author = users.next()
        val voter = users.next()
        val teacher = em.persistUser(TEACHER, createdAt = NOW)
        val id = published(author)
        val copy = copy()

        assertEquals(1 to 1, service.vote(voter.id, id, 1).reviews.single { it.id == id }.let { it.score to it.myVote })
        assertEquals(-1 to -1, service.vote(voter.id, id, -1).reviews.single { it.id == id }.let { it.score to it.myVote })
        assertEquals(0 to 0, service.vote(voter.id, id, 0).reviews.single { it.id == id }.let { it.score to it.myVote })
        assertFailsWith<InvalidRequestDataException> { service.vote(voter.id, id, 2) }
        assertFailsWith<BusinessRuleException> { service.vote(author.id, id, 1) }
        assertFailsWith<BusinessRuleException> { service.vote(teacher.id, id, 1) }
        assertFailsWith<BusinessRuleException> { service.vote(teacher.id, copy.id, 1) }
        val pending = save(users.next(), text = OTHER_TEXT).mine!!.id
        assertFailsWith<NotFoundException> { service.vote(voter.id, pending, 1) }
        assertFailsWith<NotFoundException> { service.vote(voter.id, UUID.randomUUID(), 1) }

        val onCopy = service.vote(voter.id, copy.id, 1).reviews.single { it.id == copy.id }
        assertEquals(1 to 1, onCopy.score to onCopy.myVote)
        assertEquals(1, em.find(ExternalTeacherReviewEntity::class.java, copy.id)!!.score)
        assertEquals(0, service.vote(voter.id, copy.id, 0).reviews.single { it.id == copy.id }.score)
    }

    @Test
    fun `a low score opens a votes case on own reviews only`() {
        val id = published(users.next())
        val shown = revisions.findLatestApproved(id)!!.id
        val copy = copy()
        val voters = List(3) { users.next() }

        voters.take(2).forEach { service.vote(it.id, id, -1) }
        assertNull(cases.findOpen(TYPE, shown))
        service.vote(voters[2].id, id, -1)
        assertEquals(ModerationCaseReason.VOTES, cases.findOpen(TYPE, shown)?.reason)

        voters.forEach { service.vote(it.id, copy.id, -1) }
        assertEquals(-3, em.find(ExternalTeacherReviewEntity::class.java, copy.id)!!.score)
        assertTrue(cases.findAll().none { it.targetId == copy.id })
    }

    @Test
    fun `own reviews and copies share one ranked list without the viewer's own review`() {
        val viewer = users.next()
        val top = published(users.next())
        val newer = published(users.next(), text = OTHER_TEXT)
        val own = published(viewer, text = THIRD_TEXT)
        val oldCopy = copy(writtenOn = LocalDate.of(2024, 5, 1))
        val newCopy = copy(writtenOn = LocalDate.of(2026, 9, 25))
        val undated = copy()
        service.vote(users.next().id, top, 1)
        service.vote(users.next().id, oldCopy.id, 1)
        service.vote(users.next().id, oldCopy.id, 1)

        val response = reviews(viewer)

        assertEquals(listOf(oldCopy.id, top, newCopy.id, newer, undated.id), response.reviews.map { it.id })
        assertEquals(own, response.mine?.id)
    }

    @Test
    fun `reports go to the shown revision and copies cannot be reported`() {
        val author = users.next()
        val id = published(author)
        val shown = revisions.findLatestApproved(id)!!.id
        val reporters = List(3) { users.next() }

        val reported = service.report(reporters[0].id, id, ModerationReportRequest(ReportReason.OFFENSIVE, "Грубо"))
        assertTrue(reported.reviews.single().reportedByMe)
        assertFalse(reviews(reporters[1]).reviews.single().reportedByMe)
        assertFailsWith<InvalidRequestDataException> { service.report(reporters[1].id, id, ModerationReportRequest(ReportReason.BROKEN)) }
        assertFailsWith<BusinessRuleException> { service.report(author.id, id, ModerationReportRequest(ReportReason.SPAM)) }
        assertFailsWith<BusinessRuleException> { service.report(reporters[1].id, copy().id, ModerationReportRequest(ReportReason.SPAM)) }
        assertFailsWith<NotFoundException> {
            service.report(reporters[1].id, UUID.randomUUID(), ModerationReportRequest(ReportReason.SPAM))
        }
        assertNull(cases.findOpen(TYPE, shown))

        service.report(reporters[1].id, id, ModerationReportRequest(ReportReason.WRONG_TEACHER))
        service.report(reporters[2].id, id, ModerationReportRequest(ReportReason.SPAM))

        assertEquals(ModerationCaseReason.REPORTS, cases.findOpen(TYPE, shown)?.reason)
        assertEquals(3, reportRows.findActive(TYPE, shown).size)
    }

    @Test
    fun `a hidden review is shown only to its author as hidden`() {
        val author = users.next()
        val reader = users.next()
        val id = published(author)
        val case = moderation.openCase(TYPE, revisions.findLatestApproved(id)!!.id, ModerationCaseReason.REPORTS)

        moderation.decide(moderator.id, case.id, ModerationDecisionRequest(ModerationAction.HIDE))

        assertTrue(reviews(reader).reviews.isEmpty())
        assertEquals(TeacherReviewStatus.HIDDEN, reviews(author).mine?.status)
        assertFailsWith<NotFoundException> { service.vote(reader.id, id, 1) }
        moderation.decide(moderator.id, case.id, ModerationDecisionRequest(ModerationAction.RESTORE))
        assertEquals(listOf(id), reviews(reader).reviews.map { it.id })
    }

    @Test
    fun `hiding everything by an author hides published reviews and rejects pending revisions`() {
        val author = users.next()
        val reader = users.next()
        val shown = published(author)
        val pending = save(author, text = OTHER_TEXT, isu = OTHER_TEACHER).mine!!.id
        val second = save(author, text = THIRD_TEXT, isu = THIRD_TEACHER).mine!!.id
        val case = cases.findOpen(TYPE, revisions.findPending(pending)!!.id)!!
        val otherCase = cases.findOpen(TYPE, revisions.findPending(second)!!.id)!!

        moderation.decide(moderator.id, case.id, ModerationDecisionRequest(ModerationAction.HIDE_ALL_BY_USER, note = "Спам"))

        assertTrue(reviews(reader).reviews.isEmpty())
        assertEquals(shown, reviews(author).mine?.id)
        assertEquals(TeacherReviewStatus.HIDDEN, reviews(author).mine?.status)
        assertEquals(TeacherReviewStatus.REJECTED, service.reviews(author.id, OTHER_TEACHER).mine?.status)
        assertEquals("Спам", service.reviews(author.id, OTHER_TEACHER).mine?.reviewNote)
        assertEquals(TeacherReviewStatus.REJECTED, service.reviews(author.id, THIRD_TEACHER).mine?.status)
        assertEquals(ModerationCaseStatus.OPEN, cases.findById(case.id).orElseThrow().status)
        assertEquals(ModerationCaseStatus.WITHDRAWN, cases.findById(otherCase.id).orElseThrow().status)
    }

    @Test
    fun `deleting a review withdraws its cases and removes reports revisions and votes`() {
        val author = users.next()
        val reader = users.next()
        val id = published(author)
        val approved = revisions.findLatestApproved(id)!!.id
        service.report(reader.id, id, ModerationReportRequest(ReportReason.OTHER))
        service.vote(reader.id, id, 1)
        save(author, text = OTHER_TEXT)
        val pending = revisions.findPending(id)!!.id
        assertNotNull(cases.findOpen(TYPE, pending))

        val response = service.delete(author.id, TEACHER)
        em.flush()
        em.clear()

        assertNull(response.mine)
        assertNull(cases.findOpen(TYPE, pending))
        assertEquals(ModerationCaseStatus.WITHDRAWN, cases.findAll().single { it.targetId == pending }.status)
        assertTrue(reportRows.findActive(TYPE, approved).isEmpty())
        assertTrue(revisions.findAllByReview(id).isEmpty())
        assertFalse(reviewRows.existsById(id))
        assertNull(em.find(TeacherReviewVoteEntity::class.java, TeacherReviewVoteId(id, reader.id)))
        assertTrue(reviews(reader).reviews.isEmpty())
        assertNull(service.delete(author.id, TEACHER).mine)
    }

    @Test
    fun `saving an unverified review queues a new check while a verified one stays`() {
        val author = users.next()
        val id = save(author).mine!!.id
        reviewRows.findById(id).orElseThrow().apply {
            verification = ReviewVerification.UNVERIFIED
            verificationDueAt = null
            verificationAttempts = 3
            verificationCheckedAt = NOW.minusSeconds(3600)
        }
        em.flush()

        save(author, anonymous = false)
        val queued = reviewRows.findById(id).orElseThrow()
        assertEquals(ReviewVerification.PENDING, queued.verification)
        assertEquals(NOW, queued.verificationDueAt)
        assertEquals(0, queued.verificationAttempts)

        queued.apply {
            verification = ReviewVerification.VERIFIED
            verifiedFlowId = 93724
            verificationDueAt = null
        }
        em.flush()
        assertTrue(save(author, text = OTHER_TEXT).mine!!.verified)
        val verified = reviewRows.findById(id).orElseThrow()
        assertEquals(ReviewVerification.VERIFIED, verified.verification)
        assertEquals(93724L, verified.verifiedFlowId)
    }

    @Test
    fun `capabilities follow the viewer and restrictions`() {
        val teacher = em.persistUser(TEACHER, createdAt = NOW)
        val writer = users.next()
        val voter = users.next()
        val reporter = users.next()
        val everything = users.next()
        restrict(writer, RestrictionCapability.WRITE_REVIEWS)
        restrict(voter, RestrictionCapability.VOTE)
        restrict(reporter, RestrictionCapability.REPORT)
        restrict(everything, RestrictionCapability.ALL)

        fun flags(user: User) = reviews(user).let { Triple(it.canWrite, it.canVote, it.canReport) }
        assertEquals(Triple(true, true, true), flags(users.next()))
        assertEquals(Triple(false, false, true), flags(teacher))
        assertEquals(Triple(false, true, true), flags(writer))
        assertEquals(Triple(true, false, true), flags(voter))
        assertEquals(Triple(true, true, false), flags(reporter))
        assertEquals(Triple(false, false, false), flags(everything))
    }

    @Test
    fun `a teacher is known from lessons the ISU cache copies and published reviews`() {
        val viewer = users.next()
        lesson(teacherIsu = 142001)
        em.persist(IsuPotokEntity(93724, teachersCheckedAt = NOW))
        em.persist(IsuPotokTeacherEntity(IsuPotokTeacherId(93724, 142002)))
        copy(isu = 142003)
        published(users.next(), isu = 142004)
        save(users.next(), isu = 142005)
        em.flush()

        assertTrue(service.reviews(viewer.id, 142001).knownTeacher)
        assertTrue(service.reviews(viewer.id, 142002).knownTeacher)
        assertTrue(service.reviews(viewer.id, 142003).knownTeacher)
        assertTrue(service.reviews(viewer.id, 142004).knownTeacher)
        assertFalse(service.reviews(viewer.id, 142005).knownTeacher)
        assertFalse(service.reviews(viewer.id, 142006).knownTeacher)
    }

    @Test
    fun `teacher reviews cannot leave premoderation`() {
        val request = ModerationSettings(
            mapOf(
                ModerationTargetType.SUBJECT_RESOURCE to ModerationPolicy(),
                ModerationTargetType.TEACHER_REVIEW to ModerationPolicy(premoderation = false, reportThreshold = 5),
            ),
        )

        val error = assertFailsWith<InvalidRequestDataException> { settings.update(moderator.id, request) }

        assertEquals("Teacher reviews are always premoderated", error.message)
        assertEquals(ModerationPolicy(), settings.policy(ModerationTargetType.TEACHER_REVIEW))
    }

    @Test
    fun `nonpositive isus are rejected`() {
        val viewer = users.next()
        for (isu in listOf(0, -1)) {
            assertEquals("ISU must be positive", assertFailsWith<InvalidRequestDataException> { service.reviews(viewer.id, isu) }.message)
        }
    }

    @Test
    fun `the shown AI summary comes with every response and disappears when hidden or ineligible`() {
        val viewer = users.next()
        assertNull(reviews(viewer).summary)
        // The previous content stays shown with its own count while the input has changed.
        val row = summary(TEACHER, inputCount = 6, contentCount = 5)
        val expected = TeacherSummary(
            reviewCount = 5, description = SUMMARY_TEXT, pros = listOf("Понятные лекции"), cons = emptyList(),
            tags = listOf(SummaryTag.AUTOMAT),
            scales = SummaryScaleKind.entries.map { TeacherSummaryScale(it, SummaryScaleValue.NOT_ENOUGH_DATA, null) },
            level = SummaryLevel.POSITIVE, confidence = SummaryConfidence.MEDIUM, generatedAt = NOW,
        )
        assertEquals(expected, reviews(viewer).summary)

        val copy = copy()
        assertEquals(expected, save(users.next()).summary)
        assertEquals(expected, service.vote(viewer.id, copy.id, 1).summary)
        assertNull(reviews(viewer, OTHER_TEACHER).summary)

        row.hiddenAt = NOW
        em.flush()
        assertNull(reviews(viewer).summary)
        row.hiddenAt = null
        row.inputHash = null
        row.inputCount = 0
        em.flush()
        assertNull(reviews(viewer).summary)
        val empty = em.persistAndFlush(
            TeacherSummaryEntity(
                teacherIsu = OTHER_TEACHER,
                inputHash = "c".repeat(64),
                inputCount = 3,
                updatedAt = NOW,
            ),
        )
        assertNull(reviews(viewer, empty.teacherIsu).summary)
    }

    @Test
    fun `summary levels take up to 50 distinct ISU numbers`() {
        summary(TEACHER, inputCount = 5, contentCount = 5)
        summary(OTHER_TEACHER, inputCount = 3, contentCount = 3, confidence = SummaryConfidence.LOW)

        assertEquals(
            listOf(TeacherSummaryLevel(TEACHER, SummaryLevel.POSITIVE)),
            service.summaryLevels(listOf(OTHER_TEACHER, TEACHER, TEACHER, THIRD_TEACHER)),
        )
        assertEquals(emptyList(), service.summaryLevels((1..50).map { 470_000 + it }))
        for (invalid in listOf(emptyList(), (1..51).map { 470_000 + it }, listOf(99_999), listOf(TEACHER, 10_000_000))) {
            assertEquals(
                "Invalid teacher ISU list",
                assertFailsWith<InvalidRequestDataException> {
                    service.summaryLevels(invalid)
                }.message,
            )
        }
    }

    private fun summary(
        isu: Int,
        inputCount: Int,
        contentCount: Int,
        confidence: SummaryConfidence = SummaryConfidence.MEDIUM,
    ): TeacherSummaryEntity = em.persistAndFlush(
        TeacherSummaryEntity(
            teacherIsu = isu, inputHash = "b".repeat(64), inputCount = inputCount,
            content = jacksonMapperBuilder().build().writeValueAsString(
                StoredSummary(
                    description = SUMMARY_TEXT,
                    pros = listOf("Понятные лекции"),
                    cons = emptyList(),
                    tags = listOf(SummaryTag.AUTOMAT),
                    scales = SummaryScaleKind.entries.map { StoredScale(it, SummaryScaleValue.NOT_ENOUGH_DATA, null) },
                ),
            ),
            contentHash = "a".repeat(64), contentCount = contentCount, level = SummaryLevel.POSITIVE, confidence = confidence,
            model = "gemini-test-model", generatedAt = NOW, updatedAt = NOW,
        ),
    )

    private fun reviews(viewer: User, isu: Int = TEACHER): TeacherReviewsResponse = service.reviews(viewer.id, isu)

    private fun save(
        author: User,
        text: String = TEXT,
        subject: String? = null,
        anonymous: Boolean = true,
        flows: List<Long> = emptyList(),
        isu: Int = TEACHER,
    ): TeacherReviewsResponse = service.save(author.id, isu, SaveTeacherReviewRequest(subject, text, anonymous, flows))

    /** A review of [author] with one approved revision. */
    private fun published(author: User, text: String = TEXT, isu: Int = TEACHER): UUID {
        val id = save(author, text = text, isu = isu).mine!!.id
        decide(id, ModerationAction.APPROVE)
        return id
    }

    private fun decide(reviewId: UUID, action: ModerationAction, note: String? = null) {
        val revision = revisions.findPending(reviewId) ?: error("No pending revision")
        val case = cases.findOpen(TYPE, revision.id) ?: error("No open case")
        moderation.decide(moderator.id, case.id, ModerationDecisionRequest(action, note))
    }

    private fun dailyLimit(limit: Int) = settings.update(
        moderator.id,
        ModerationSettings(
            mapOf(
                ModerationTargetType.SUBJECT_RESOURCE to ModerationPolicy(),
                ModerationTargetType.TEACHER_REVIEW to ModerationPolicy(dailySubmissionLimit = limit),
            ),
        ),
    )

    private fun copy(isu: Int = TEACHER, writtenOn: LocalDate? = null) = em.persistAndFlush(
        ExternalTeacherReviewEntity(
            provider = ReviewProvider.REVIEWS_WORK_GD, externalId = nextExternalId++, teacherIsu = isu,
            teacherName = "Synthetic teacher", subjectTitle = null, sourceTitle = null, sourceLink = null,
            dateRaw = "", writtenOn = writtenOn, writtenBeforeYear = null, text = "Synthetic copied review",
            firstSeenAt = NOW, lastSeenAt = NOW,
        ),
    )

    private fun lesson(teacherIsu: Long) = em.persist(
        LessonEntity(
            userIsu = 965999, date = LocalDate.of(2026, 9, 21), pairId = 9_650_001L, subjectId = 1, subjectName = "Synthetic subject",
            teacherIsu = teacherIsu, teacherFio = null, start = LocalTime.of(10, 0), end = LocalTime.of(11, 30), type = "Лекция",
            typeId = 1, groupName = "M3100", flowId = 93724, flowTypeId = 2, note = null, room = null, building = null,
            buildingId = null, mainBuildingId = null, format = "Очно", formatId = 1,
        ),
    )

    private fun restrict(user: User, capability: RestrictionCapability) {
        val case = em.persist(
            ModerationCaseEntity(
                targetType = ModerationTargetType.SUBJECT_RESOURCE,
                targetId = UUID.randomUUID(),
                reason = ModerationCaseReason.REPORTS,
                openedAt = NOW,
            ),
        )
        val decision = em.persist(
            ModerationDecisionEntity(
                case = case,
                moderator = moderator,
                action = ModerationAction.RESTRICT_USER,
                restrictionCapability = capability,
                createdAt = NOW,
            ),
        )
        em.persistAndFlush(
            UserRestrictionEntity(
                user = user,
                capability = capability,
                decision = decision,
                reason = "Правила",
                startsAt = NOW.minusSeconds(60),
            ),
        )
    }

    private companion object {
        val TYPE = ModerationTargetType.TEACHER_REVIEW

        /** 01:30 in Moscow on September 23. */
        val NOW: Instant = Instant.parse("2026-09-22T22:30:00Z")
        const val TEACHER = 142415
        const val OTHER_TEACHER = 471029
        const val THIRD_TEACHER = 471030
        const val TEXT = "Объясняет понятно, на вопросы отвечает подробно."
        const val OTHER_TEXT = "Строгий, но справедливый; лабораторные принимает вовремя."
        const val THIRD_TEXT = "Лекции интересные, материалы выкладывает заранее."
        const val SUMMARY_TEXT = "Студенты отмечают понятные лекции и доброжелательное отношение."
    }
}
