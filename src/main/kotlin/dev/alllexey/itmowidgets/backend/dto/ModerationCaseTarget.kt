package dev.alllexey.itmowidgets.backend.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/** The discriminator is the case's target type; every target service contributes one subtype. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "targetType")
@JsonSubTypes(JsonSubTypes.Type(value = SubjectLinkTarget::class, name = "SUBJECT_RESOURCE"))
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

data class SubmitterHistory(
    val approved: Long,
    val rejected: Long,
    val dismissedReports: Long,
    val activeRestrictions: List<UserRestriction>,
)
