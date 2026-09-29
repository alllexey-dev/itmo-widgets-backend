package dev.alllexey.itmowidgets.backend.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/** The discriminator is the case's target type; every target service contributes one subtype. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "targetType")
@JsonSubTypes(
    JsonSubTypes.Type(value = SubjectLinkTarget::class, name = "SUBJECT_RESOURCE"),
    JsonSubTypes.Type(value = TeacherReviewTarget::class, name = "TEACHER_REVIEW"),
)
sealed interface ModerationCaseTarget

/**
 * A subject link revision under review. [link] shows the content other students currently see
 * and the owner-side status; [revision] is the exact content the case is about.
 */
data class SubjectLinkTarget(
    val revision: SubjectLinkRevision,
    val link: SubjectLink,
    val author: UserData,
    val reports: List<ModerationReport>,
    val submitterHistory: SubmitterHistory,
) : ModerationCaseTarget

/**
 * A teacher review revision under review. [author] is shown to moderators and admins even for an
 * anonymous review; [review] carries the content others currently see and the author-side status.
 */
data class TeacherReviewTarget(
    val revision: TeacherReviewRevision,
    val review: ModeratedTeacherReview,
    val author: UserData,
    val reports: List<ModerationReport>,
    val submitterHistory: SubmitterHistory,
) : ModerationCaseTarget

data class SubmitterHistory(
    val approved: Long,
    val rejected: Long,
    val dismissedReports: Long,
    val activeRestrictions: List<UserRestriction>,
)
