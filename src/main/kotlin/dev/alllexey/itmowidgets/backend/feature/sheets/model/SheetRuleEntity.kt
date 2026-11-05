package dev.alllexey.itmowidgets.backend.feature.sheets.model

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

/** The job state of one sheet; the database values of `sheet_rules.status`. */
enum class SheetRuleStatus { PENDING, RUNNING, READY, INSUFFICIENT, FAILED }

/**
 * The AI rule of one Google sheet. [rule] is the analyzer's sanitised answer as JSON text; [fingerprint], [model] and
 * [analyzedAt] belong to the stored verdict (a rule, or none for `INSUFFICIENT`). Rows change only through
 * column-scoped repository updates.
 */
@Entity
@Table(name = "sheet_rules")
class SheetRuleEntity(
    @Id val sheetId: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var status: SheetRuleStatus,
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var rule: String? = null,
    @Column(length = 64) var fingerprint: String? = null,
    @Column(length = 100) var model: String? = null,
    @Column(nullable = false) var attempts: Int = 0,
    /** A reason code such as `closed` or `busy`, never analyzer or model text. */
    @Column(length = 100) var lastError: String? = null,
    var requestedAt: Instant? = null,
    var checkedAt: Instant? = null,
    var analyzedAt: Instant? = null,
    @Column(nullable = false) var updatedAt: Instant,
) {
    /**
     * Whether a request queues this sheet again: a verdict checked before [staleBefore] or a failure older than it.
     * `SheetRuleRepository.requeue` applies the same rule atomically.
     */
    fun isDue(staleBefore: Instant): Boolean = when (status) {
        SheetRuleStatus.READY, SheetRuleStatus.INSUFFICIENT -> checkedAt?.isBefore(staleBefore) == true
        SheetRuleStatus.FAILED -> updatedAt.isBefore(staleBefore)
        SheetRuleStatus.PENDING, SheetRuleStatus.RUNNING -> false
    }

    override fun toString(): String = "SheetRuleEntity($status)"
}

/** The running lease of the analysis job and the day budget of analyses; the migration creates the only row. */
@Entity
@Table(name = "sheet_rule_state")
class SheetRuleStateEntity(
    @Id val id: Short = ID,
    var runningSince: Instant? = null,
    @JdbcTypeCode(SqlTypes.LOCAL_DATE)
    var budgetDay: LocalDate? = null,
    @Column(nullable = false) var budgetUsed: Int = 0,
) {
    companion object {
        const val ID: Short = 1
    }
}
