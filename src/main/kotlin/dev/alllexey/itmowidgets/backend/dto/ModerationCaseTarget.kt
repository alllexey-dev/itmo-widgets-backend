package dev.alllexey.itmowidgets.backend.dto

import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "targetType")
interface ModerationCaseTarget

data class SubmitterHistory(
    val approved: Long,
    val rejected: Long,
    val dismissedReports: Long,
    val activeRestrictions: List<UserRestriction>,
)
