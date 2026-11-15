package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSheetRuleError
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSheetRules
import dev.alllexey.itmowidgets.backend.feature.sheets.service.SheetRuleStatistics
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** The sheet rules panel of the system page: authorizes and maps [SheetRuleStatistics] to the wire. */
@Service
class AdminSheetRulesService(private val access: AdminAccess, private val sheetRules: SheetRuleStatistics) {
    @Transactional(readOnly = true)
    fun sheetRules(adminId: UUID): AdminSheetRules {
        access.requireAdmin(adminId)
        val stats = sheetRules.stats()
        return AdminSheetRules(
            enabled = stats.enabled,
            running = stats.runningSince != null,
            runningSince = stats.runningSince,
            statuses = stats.statuses,
            attempts = stats.attempts,
            lastAnalyzedAt = stats.lastAnalyzedAt,
            errors = stats.errors.map { AdminSheetRuleError(it.error, it.sheets) },
            budgetDay = stats.budgetDay,
            budgetUsed = stats.budgetUsed,
            dailyBudget = stats.dailyBudget,
        )
    }
}
