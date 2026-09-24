package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.AdminRestriction
import dev.alllexey.itmowidgets.backend.repositories.AdminRestrictionRow
import org.springframework.stereotype.Component
import java.time.Clock

/** Restriction rows with their users resolved in one lookup. Callers check roles. */
@Component
class AdminRestrictionViews(private val summaries: AdminUserSummaries, private val clock: Clock) {
    fun of(rows: List<AdminRestrictionRow>): List<AdminRestriction> {
        val now = clock.instant()
        val users = summaries.of(rows.map { it.userId })
        return rows.map { row ->
            AdminRestriction(row.id, users.getValue(row.userId), row.capability, row.reason, row.startsAt, row.expiresAt,
                row.revokedAt, row.revokedByIsu,
                active = row.revokedAt == null && row.startsAt <= now && (row.expiresAt?.let { it > now } ?: true),
                caseId = row.caseId)
        }
    }
}
