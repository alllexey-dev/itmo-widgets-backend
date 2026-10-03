package dev.alllexey.itmowidgets.backend.feature.reviews.persistence

import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewProvider
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewRevisionStatus
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewVerification
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewRevisionEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.service.AiSummaryConfig
import dev.alllexey.itmowidgets.backend.feature.reviews.service.SummaryInputReview
import dev.alllexey.itmowidgets.backend.feature.reviews.service.SummaryInputSource
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReviewKind
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

@Import(SummaryInputSource::class, SummaryInputPersistenceTest.TestConfig::class)
@TestPropertySource(properties = ["itmowidgets.ai-summary.model=gemini-test-model"])
class SummaryInputPersistenceTest @Autowired constructor(
    private val em: TestEntityManager,
    private val source: SummaryInputSource,
    private val external: ExternalTeacherReviewRepository,
    private val reviews: TeacherReviewRepository,
    private val revisions: TeacherReviewRevisionRepository,
    private val config: AiSummaryConfig,
    private val clock: Clock,
) : PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSummaryConfig::class)
    class TestConfig {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneId.of("Europe/Moscow"))
    }

    @Test
    fun `active copies and verified own reviews go in with the approved content`() {
        val copy = copy(TEACHER, LocalDate.of(2026, 3, 10), subject = "Математика")
        val verified = own(TEACHER, verification = ReviewVerification.VERIFIED, submittedAt = Instant.parse("2026-02-28T22:30:00Z"))
        val beforeYear = copy(TEACHER, null, beforeYear = 2024)
        copy(TEACHER, LocalDate.of(2026, 4, 1), removed = true)
        own(TEACHER, verification = ReviewVerification.PENDING)
        own(TEACHER, verification = ReviewVerification.UNVERIFIED)
        own(TEACHER, verification = ReviewVerification.VERIFIED, hidden = true)
        own(TEACHER, verification = ReviewVerification.VERIFIED, approved = false)
        copy(OTHER_TEACHER, LocalDate.of(2026, 5, 1))
        flush()

        val input = assertNotNull(source.forTeacher(TEACHER))

        assertEquals(TEACHER, input.teacherIsu)
        assertEquals(3, input.count)
        assertEquals(
            listOf(
                SummaryInputReview(TeacherReviewKind.REVIEWS, copy.id, "Математика", "2026-03", copy.text),
                // Moscow date of the approved revision, its subject and text, not the author's current ones.
                SummaryInputReview(TeacherReviewKind.COMMUNITY, verified.id, "Одобренный предмет", "2026-03", APPROVED_TEXT),
                SummaryInputReview(TeacherReviewKind.REVIEWS, beforeYear.id, null, "до 2024", beforeYear.text),
            ),
            input.reviews,
        )
        assertEquals(input, source.all()[TEACHER])
        assertNull(source.all()[OTHER_TEACHER])
    }

    @Test
    fun `a teacher needs at least three reviews`() {
        copy(TEACHER, LocalDate.of(2026, 1, 1))
        copy(TEACHER, LocalDate.of(2026, 1, 2))
        flush()
        assertNull(source.forTeacher(TEACHER))
        assertFalse(TEACHER in source.all())

        copy(TEACHER, LocalDate.of(2026, 1, 3))
        flush()
        assertEquals(3, assertNotNull(source.forTeacher(TEACHER)).count)
        assertEquals(3, source.all().getValue(TEACHER).count)
    }

    @Test
    fun `reviews go newest first, a copy before an own review of the same date and undated last`() {
        val undated = copy(TEACHER, null)
        val older = copy(TEACHER, LocalDate.of(2025, 9, 1))
        val ownSameDay = own(TEACHER, verification = ReviewVerification.VERIFIED, submittedAt = Instant.parse("2026-09-10T09:00:00Z"))
        val copySameDay = copy(TEACHER, LocalDate.of(2026, 9, 10))
        flush()

        assertEquals(listOf(copySameDay.id, ownSameDay.id, older.id, undated.id), source.forTeacher(TEACHER)?.reviews?.map { it.id })
        assertEquals(null, source.forTeacher(TEACHER)?.reviews?.last()?.date)
    }

    @Test
    fun `the review and character limits keep the newest reviews`() {
        val copies = (0 until 61).map { copy(TEACHER, LocalDate.of(2026, 1, 1).plusDays(it.toLong())) }
        flush()

        val limited = assertNotNull(source.forTeacher(TEACHER))
        assertEquals(60, limited.count)
        assertEquals(copies.drop(1).reversed().map { it.id }, limited.reviews.map { it.id })

        val narrow = SummaryInputSource(external, reviews, revisions, config.copy(maxInputChars = 1000), clock)
        val long = (0 until 4).map { copy(OTHER_TEACHER, LocalDate.of(2026, 2, 1).plusDays(it.toLong()), text = "я".repeat(300)) }
        flush()
        assertEquals(long.takeLast(3).reversed().map { it.id }, narrow.forTeacher(OTHER_TEACHER)?.reviews?.map { it.id })
    }

    @Test
    fun `the hash is stable and follows the text, approvals, hiding and the model`() {
        val copy = copy(TEACHER, LocalDate.of(2026, 1, 1))
        copy(TEACHER, LocalDate.of(2026, 1, 2))
        copy(TEACHER, LocalDate.of(2026, 1, 3))
        val verified = own(TEACHER, verification = ReviewVerification.VERIFIED)
        flush()
        val first = assertNotNull(source.forTeacher(TEACHER)).hash
        assertEquals(64, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertEquals(first, source.forTeacher(TEACHER)?.hash)
        assertEquals(first, source.all().getValue(TEACHER).hash)

        external.findById(copy.id).orElseThrow().text = "Изменённый синтетический текст копии"
        flush()
        val edited = assertNotNull(source.forTeacher(TEACHER)).hash
        assertNotEquals(first, edited)

        revision(reviews.findById(verified.id).orElseThrow(), 2, ReviewRevisionStatus.APPROVED, "Новая одобренная версия отзыва")
        flush()
        val approved = assertNotNull(source.forTeacher(TEACHER)).hash
        assertNotEquals(edited, approved)

        reviews.findById(verified.id).orElseThrow().hiddenAt = NOW
        flush()
        val hidden = assertNotNull(source.forTeacher(TEACHER))
        assertEquals(3, hidden.count)
        assertNotEquals(approved, hidden.hash)

        val otherModel = SummaryInputSource(external, reviews, revisions, config.copy(model = "gemini-other-model"), clock)
        assertNotEquals(hidden.hash, otherModel.forTeacher(TEACHER)?.hash)
    }

    private fun flush() {
        em.flush()
        em.clear()
    }

    private fun copy(
        teacherIsu: Int,
        writtenOn: LocalDate?,
        beforeYear: Int? = null,
        subject: String? = null,
        removed: Boolean = false,
        text: String = "Синтетическая копия отзыва ${EXTERNAL_IDS.get()}",
    ) = em.persist(
        ExternalTeacherReviewEntity(
            provider = ReviewProvider.REVIEWS_WORK_GD, externalId = EXTERNAL_IDS.incrementAndGet(), teacherIsu = teacherIsu,
            teacherName = "Synthetic teacher", subjectTitle = subject, sourceTitle = null, sourceLink = null, dateRaw = "",
            writtenOn = writtenOn, writtenBeforeYear = beforeYear, text = text, firstSeenAt = NOW, lastSeenAt = NOW,
            removedAt = if (removed) NOW else null,
        ),
    )

    private fun own(
        teacherIsu: Int,
        verification: ReviewVerification,
        hidden: Boolean = false,
        approved: Boolean = true,
        submittedAt: Instant = NOW,
    ): TeacherReviewEntity {
        val author = em.persist(
            User(
                isu = AUTHOR_ISUS.incrementAndGet().toInt(),
                name = "Synthetic author",
                pictureUrl = null,
                createdAt = NOW,
            ).apply { settings = UserSettingsEntity(user = this) },
        )
        val review = em.persist(
            TeacherReviewEntity(
                author = author, teacherIsu = teacherIsu, subjectTitle = "Текущий предмет автора",
                text = "Текущий текст автора, ещё не одобренный модератором", hiddenAt = if (hidden) NOW else null,
                verification = verification, verifiedFlowId = 93724L.takeIf { verification == ReviewVerification.VERIFIED },
                verificationDueAt = NOW.takeIf { verification == ReviewVerification.PENDING }, createdAt = NOW, updatedAt = NOW,
            ),
        )
        revision(review, 1, if (approved) ReviewRevisionStatus.APPROVED else ReviewRevisionStatus.PENDING, APPROVED_TEXT, submittedAt)
        return review
    }

    private fun revision(review: TeacherReviewEntity, number: Int, status: ReviewRevisionStatus, text: String, submittedAt: Instant = NOW) =
        em.persist(
            TeacherReviewRevisionEntity(
                review = review,
                number = number,
                subjectTitle = "Одобренный предмет",
                text = text,
                status = status,
                submittedAt = submittedAt,
                decidedAt = submittedAt.takeIf { status != ReviewRevisionStatus.PENDING },
            ),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-29T09:00:00Z")
        const val TEACHER = 965900
        const val OTHER_TEACHER = 965901
        const val APPROVED_TEXT = "Одобренный синтетический отзыв о преподавателе для теста"
        val EXTERNAL_IDS = AtomicLong(9_659_000)
        val AUTHOR_ISUS = AtomicLong(965_000)
    }
}
