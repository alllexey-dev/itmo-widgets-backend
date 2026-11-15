package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.persistence.AdminRestrictionRow
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminRestriction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.restrictionInForce
import org.springframework.stereotype.Component
import java.time.Clock

/** Restriction rows with their users resolved in one lookup. Callers check roles. */
@Component
class AdminRestrictionViews(private val summaries: AdminUserSummaries, private val clock: Clock) {
    fun of(rows: List<AdminRestrictionRow>): List<AdminRestriction> {
        val now = clock.instant()
        val users = summaries.of(rows.map { it.userId })
        return rows.map { row ->
            AdminRestriction(
                row.id, users.getValue(row.userId), row.capability, row.reason, row.startsAt, row.expiresAt,
                row.revokedAt, row.revokedByIsu, row.revokedByName,
                active = restrictionInForce(row.startsAt, row.expiresAt, row.revokedAt, now),
                caseId = row.caseId,
            )
        }
    }
}
