package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.ExternalTeacherReviewEntity
import java.time.LocalDate

object ReviewOrder {
    val NEWEST_FIRST: Comparator<ExternalTeacherReviewEntity> =
        compareByDescending<ExternalTeacherReviewEntity> {
            it.writtenOn ?: it.writtenBeforeYear?.let { year -> LocalDate.of(year, 1, 1) }
        }.thenByDescending { it.writtenOn != null }
            .thenByDescending { it.externalId }
}
