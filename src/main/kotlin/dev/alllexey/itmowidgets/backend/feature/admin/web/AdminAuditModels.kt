// One models file per admin page (docs/architecture.md), even while the page has one type.
@file:Suppress("ktlint:standard:filename")

package dev.alllexey.itmowidgets.backend.feature.admin.web

import java.time.Instant
import java.util.UUID

data class AdminAuditEntry(
    val id: UUID,
    val action: String,
    val target: String,
    val details: String?,
    val createdAt: Instant,
    val actorIsu: Int,
    val actorName: String,
)
