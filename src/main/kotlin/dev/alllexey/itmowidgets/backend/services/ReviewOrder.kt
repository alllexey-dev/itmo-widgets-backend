package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.TeacherReview
import dev.alllexey.itmowidgets.backend.dto.TeacherReviewKind
import java.time.LocalDate

object ReviewOrder {
    /**
     * Own reviews and Reviews copies in one list: score first, then the newest date (`до Y` counts as
     * January 1 of Y, undated last), a real date before `до Y`, own reviews before copies, then the id.
     * Verification does not affect the order.
     */
    val RANKED: Comparator<TeacherReview> =
        compareByDescending<TeacherReview> { it.score }
            .thenBy(nullsLast(reverseOrder())) { review: TeacherReview ->
                review.writtenOn ?: review.writtenBeforeYear?.let { year -> LocalDate.of(year, 1, 1) }
            }
            .thenByDescending { it.writtenOn != null }
            .thenBy { it.kind != TeacherReviewKind.COMMUNITY }
            .thenBy { it.id.toString() }
}
