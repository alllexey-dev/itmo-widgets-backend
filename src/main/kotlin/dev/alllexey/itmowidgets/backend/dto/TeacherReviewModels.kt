package dev.alllexey.itmowidgets.backend.dto

import java.time.LocalDate
import java.util.UUID

data class TeacherReviewsResponse(
    val teacherIsu: Int,
    val providerUrl: String,
    val external: List<ExternalTeacherReview>,
)

/**
 * An anonymous copy of a provider's review. The teacher name, external id, raw date
 * and persistence timestamps are never exposed.
 */
data class ExternalTeacherReview(
    val id: UUID,
    val subjectTitle: String?,
    val writtenOn: LocalDate?,
    val writtenBeforeYear: Int?,
    val sourceTitle: String?,
    val sourceLink: String?,
    val text: String,
)
