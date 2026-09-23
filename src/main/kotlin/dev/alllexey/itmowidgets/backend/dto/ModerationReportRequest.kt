package dev.alllexey.itmowidgets.backend.dto

import dev.alllexey.itmowidgets.backend.model.*
import java.time.Instant
import java.util.UUID

data class ModerationReportRequest(val reason: ReportReason, val comment: String? = null)
