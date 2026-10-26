package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewProvider
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalTeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewRevisionRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReviewKind
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportLessonRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.HexFormat
import java.util.UUID

/** One review as the model sees it; [date] is `YYYY-MM`, `до YYYY` or null. */
data class SummaryInputReview(val kind: TeacherReviewKind, val id: UUID, val subject: String?, val date: String?, val text: String) {
    override fun toString(): String = "SummaryInputReview($kind, $id)"
}

/**
 * The reviews a teacher's summary is built from, newest first; [hash] changes whenever the prompt would. [coach]: the
 * teacher has lessons in the sport catalog, so the prompt leaves out exam and lab tags.
 */
data class TeacherSummaryInput(val teacherIsu: Int, val reviews: List<SummaryInputReview>, val hash: String, val coach: Boolean = false) {
    val count: Int get() = reviews.size

    override fun toString(): String = "TeacherSummaryInput($teacherIsu, count=$count)"
}

/**
 * Collects the summary input: active Reviews copies and own reviews that passed the ISU check, are not hidden
 * and have an approved revision, whose content is used instead of the author's current text. Pending, unverified
 * and hidden reviews never go in.
 */
@Service
@Transactional(readOnly = true)
class SummaryInputSource(
    private val external: ExternalTeacherReviewRepository,
    private val reviews: TeacherReviewRepository,
    private val revisions: TeacherReviewRevisionRepository,
    private val sportLessons: SportLessonRepository,
    private val config: AiSummaryConfig,
    private val clock: Clock,
) {
    /** Eligible teachers only. */
    fun all(): Map<Int, TeacherSummaryInput> {
        val candidates = external.findAllByProviderAndRemovedAtIsNull(ReviewProvider.REVIEWS_WORK_GD).map(::copy) +
            own(reviews.findSummaryCandidates())
        val coaches = sportLessons.findTeacherIsus().toSet()
        return candidates.groupBy { it.teacherIsu }
            .mapNotNull { (isu, rows) -> input(isu, rows, isu.toLong() in coaches) }
            .associateBy { it.teacherIsu }
    }

    /** Null when the teacher is not eligible. */
    fun forTeacher(isu: Int): TeacherSummaryInput? {
        val candidates = external.findAllByProviderAndTeacherIsuAndRemovedAtIsNull(ReviewProvider.REVIEWS_WORK_GD, isu).map(::copy) +
            own(reviews.findSummaryCandidates(isu))
        return input(isu, candidates, sportLessons.existsTeacher(isu.toLong()))
    }

    private fun copy(row: ExternalTeacherReviewEntity) = Candidate(
        teacherIsu = row.teacherIsu,
        review = SummaryInputReview(
            TeacherReviewKind.REVIEWS,
            row.id,
            row.subjectTitle,
            row.writtenOn?.let { YearMonth.from(it).toString() } ?: row.writtenBeforeYear?.let { "до $it" },
            row.text,
        ),
        sortDate = row.writtenOn ?: row.writtenBeforeYear?.let { LocalDate.of(it, 1, 1) },
    )

    private fun own(rows: List<TeacherReviewEntity>): List<Candidate> {
        if (rows.isEmpty()) return emptyList()
        val byId = rows.associateBy { it.id }
        return rows.map { it.id }.chunked(CHUNK).flatMap(revisions::findLatestApprovedIn).map { revision ->
            val date = LocalDate.ofInstant(revision.submittedAt, clock.zone)
            Candidate(
                teacherIsu = byId.getValue(revision.review.id).teacherIsu,
                review = SummaryInputReview(
                    TeacherReviewKind.COMMUNITY,
                    revision.review.id,
                    revision.subjectTitle,
                    YearMonth.from(date).toString(),
                    revision.text,
                ),
                sortDate = date,
            )
        }
    }

    private fun input(isu: Int, candidates: List<Candidate>, coach: Boolean): TeacherSummaryInput? {
        val taken = ArrayList<SummaryInputReview>()
        var chars = 0
        for (candidate in candidates.sortedWith(ORDER)) {
            val length = candidate.review.text.codePointCount(0, candidate.review.text.length)
            if (taken.size >= config.maxInputReviews || chars + length > config.maxInputChars) break
            taken += candidate.review
            chars += length
        }
        if (taken.size < MIN_INPUT) return null
        return TeacherSummaryInput(isu, taken, hash(taken, coach), coach)
    }

    private fun hash(taken: List<SummaryInputReview>, coach: Boolean): String {
        val lines = taken.joinToString("\n") { review ->
            listOf(review.kind.name, review.id.toString(), review.subject.orEmpty(), review.date.orEmpty(), sha256(review.text))
                .joinToString(FIELD_SEPARATOR)
        }
        // Only coaches get the marker, so the hashes of other teachers stay and their summaries are not rebuilt.
        val variant = if (coach) "coach\n" else ""
        return sha256("${SummaryPrompt.PROMPT_VERSION}\n${config.model}\n$variant$lines")
    }

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    private class Candidate(val teacherIsu: Int, val review: SummaryInputReview, val sortDate: LocalDate?)

    companion object {
        const val MIN_INPUT = 3
        private const val CHUNK = 500
        private const val FIELD_SEPARATOR = "\u001F"

        /** Newest first and undated last, Reviews copies before own reviews on the same date, then the id. */
        private val ORDER: Comparator<Candidate> = compareBy<Candidate, LocalDate?>(nullsLast(reverseOrder())) { it.sortDate }
            .thenBy { it.review.kind != TeacherReviewKind.REVIEWS }
            .thenBy { it.review.id.toString() }
    }
}
