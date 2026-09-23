package dev.alllexey.itmowidgets.backend.dto

import dev.alllexey.itmowidgets.backend.model.*
import java.time.Instant
import java.util.UUID

data class ModerationReport(val reason: ReportReason, val comment: String?, val createdAt: Instant)

fun ModerationReportEntity.toDto() = ModerationReport(reason, comment, createdAt)
