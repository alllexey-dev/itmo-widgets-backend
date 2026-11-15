package dev.alllexey.itmowidgets.backend.feature.sheets.service

import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleStateEntity
import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleStats
import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleStatus
import dev.alllexey.itmowidgets.backend.feature.sheets.persistence.SheetRuleStateRepository
import dev.alllexey.itmowidgets.backend.feature.sheets.persistence.SheetRuleStatisticsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/** The state of the sheet rules now: three queries in one read-only transaction, no sheet or rule loaded. */
@Service
class SheetRuleStatistics(
    private val repository: SheetRuleStatisticsRepository,
    private val states: SheetRuleStateRepository,
    private val config: SheetRulesConfig,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun stats(): SheetRuleStats {
        val statuses = repository.statuses()
        val state = states.findById(SheetRuleStateEntity.ID).orElseThrow()
        val today = LocalDate.ofInstant(clock.instant(), SheetRuleStore.BUDGET_ZONE)
        return SheetRuleStats(
            enabled = config.enabled,
            runningSince = state.runningSince,
            statuses = SheetRuleStatus.entries.associateWith { status -> statuses.firstOrNull { it.status == status }?.sheets ?: 0 },
            attempts = statuses.sumOf { it.attempts },
            lastAnalyzedAt = statuses.mapNotNull { it.lastAnalyzedAt }.maxOrNull(),
            errors = repository.errors(MAX_ERRORS),
            budgetDay = today,
            budgetUsed = if (state.budgetDay == today) state.budgetUsed else 0,
            dailyBudget = config.dailyBudget,
        )
    }

    companion object {
        /** The most frequent failure codes an answer lists. */
        const val MAX_ERRORS = 10
    }
}
