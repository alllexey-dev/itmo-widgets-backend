package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.ExternalTeacherReview
import dev.alllexey.itmowidgets.backend.dto.TeacherReviewsResponse
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.repositories.ExternalTeacherReviewRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TeacherReviewService(private val reviews: ExternalTeacherReviewRepository) {
    @Transactional(readOnly = true)
    fun reviews(isu: Int): TeacherReviewsResponse {
        if (isu <= 0) throw InvalidRequestDataException("ISU must be positive")
        val external = reviews.findAllByProviderAndTeacherIsuAndRemovedAtIsNull(ReviewsSyncStore.PROVIDER, isu)
            .sortedWith(ReviewOrder.NEWEST_FIRST)
            .map { review ->
                ExternalTeacherReview(
                    id = review.id,
                    subjectTitle = review.subjectTitle,
                    writtenOn = review.writtenOn,
                    writtenBeforeYear = review.writtenBeforeYear,
                    sourceTitle = review.sourceTitle,
                    sourceLink = review.sourceLink,
                    text = review.text,
                )
            }
        return TeacherReviewsResponse(isu, ReviewTeachers.siteUrl(isu), external)
    }
}
