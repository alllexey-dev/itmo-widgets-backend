package dev.alllexey.itmowidgets.backend.feature.sheets.model

import java.time.Instant
import java.time.LocalDate

/**
 * The state of the sheet rules now. [statuses] has every [SheetRuleStatus]; [attempts] is the sum over the sheets;
 * [errors] groups the failed sheets by their last reason code. [budgetUsed] counts the analyses of [budgetDay], today
 * in the budget zone.
 */
data class SheetRuleStats(
    val enabled: Boolean,
    val runningSince: Instant?,
    val statuses: Map<SheetRuleStatus, Long>,
    val attempts: Long,
    val lastAnalyzedAt: Instant?,
    val errors: List<SheetRuleErrorCount>,
    val budgetDay: LocalDate,
    val budgetUsed: Int,
    val dailyBudget: Int,
)

/** Failed sheets whose last reason code is [error], such as `closed` or `timeout`. */
data class SheetRuleErrorCount(val error: String, val sheets: Long)

/** Sheets of one status, their failed attempts and their latest verdict; a row the statistics repository returns. */
data class SheetRuleStatusCount(val status: SheetRuleStatus, val sheets: Long, val attempts: Long, val lastAnalyzedAt: Instant?)
