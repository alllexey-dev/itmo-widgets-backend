package dev.alllexey.itmowidgets.backend.feature.sheets.persistence

import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleErrorCount
import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleStatus
import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleStatusCount
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/** Aggregates over the sheet rules for the admin; never a sheet id or a rule. Every method is one query. */
@Repository
class SheetRuleStatisticsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** Sheets per status, with the sum of their attempts and their latest `analyzed_at`; statuses without a sheet have no row. */
    fun statuses(): List<SheetRuleStatusCount> = jdbc.query(
        """
        SELECT status, COUNT(*) AS sheets, SUM(attempts) AS attempts, MAX(analyzed_at) AS last_analyzed_at
        FROM sheet_rules GROUP BY status
        """,
    ) { rs, _ ->
        SheetRuleStatusCount(
            status = SheetRuleStatus.valueOf(rs.getString("status")),
            sheets = rs.getLong("sheets"),
            attempts = rs.getLong("attempts"),
            lastAnalyzedAt = rs.getObject("last_analyzed_at", OffsetDateTime::class.java)?.toInstant(),
        )
    }

    /** Failed sheets by their last reason code, most frequent first, then by code; at most [limit]. */
    fun errors(limit: Int): List<SheetRuleErrorCount> = jdbc.query(
        """
        SELECT last_error, COUNT(*) AS sheets FROM sheet_rules
        WHERE status = 'FAILED' AND last_error IS NOT NULL
        GROUP BY last_error
        ORDER BY sheets DESC, last_error
        LIMIT :limit
        """,
        mapOf("limit" to limit),
    ) { rs, _ -> SheetRuleErrorCount(rs.getString("last_error"), rs.getLong("sheets")) }
}
