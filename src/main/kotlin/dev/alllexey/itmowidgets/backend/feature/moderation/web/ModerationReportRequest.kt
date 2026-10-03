package dev.alllexey.itmowidgets.backend.feature.moderation.web

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ReportReason
import java.time.Instant
import java.util.UUID

data class ModerationReportRequest(val reason: ReportReason, val comment: String? = null)
