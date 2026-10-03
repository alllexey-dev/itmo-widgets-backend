package dev.alllexey.itmowidgets.backend.feature.moderation.web

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationReportEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ReportReason
import java.time.Instant
import java.util.UUID

data class ModerationReport(val reason: ReportReason, val comment: String?, val createdAt: Instant)

fun ModerationReportEntity.toDto() = ModerationReport(reason, comment, createdAt)
