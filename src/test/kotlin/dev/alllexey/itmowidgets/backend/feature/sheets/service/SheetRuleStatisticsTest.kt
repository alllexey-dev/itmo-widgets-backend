package dev.alllexey.itmowidgets.backend.feature.sheets.service

import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleErrorCount
import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleStats
import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleStatus
import dev.alllexey.itmowidgets.backend.feature.sheets.persistence.SheetRuleStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

/**
 * The sheet rule figures on real PostgreSQL. They are the state now, so each test starts from an empty table and a
 * fresh state row inside its own rolled-back transaction. The clock is 00:30 in Moscow on 2032-03-02.
 */
@Import(SheetRuleStatistics::class, SheetRuleStatisticsRepository::class, SheetRuleStatisticsTest.TestConfig::class)
@TestPropertySource(
    properties = [
        "itmowidgets.sheet-rules.enabled=true", "itmowidgets.sheet-rules.socket-path=/tmp/unused.sock",
        "itmowidgets.sheet-rules.daily-budget=7",
    ],
)
class SheetRuleStatisticsTest @Autowired constructor(private val statistics: SheetRuleStatistics, private val jdbc: JdbcTemplate) :
    PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SheetRulesConfig::class)
    class TestConfig {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2032-03-01T21:30:00Z"), ZoneOffset.UTC)
    }

    private var nextSheet = 0

    @BeforeEach
    fun emptyState() {
        jdbc.update("DELETE FROM sheet_rules")
        jdbc.update("UPDATE sheet_rule_state SET running_since = NULL, budget_day = NULL, budget_used = 0")
    }

    @Test
    fun `without sheets every status is zero and nothing runs`() {
        assertEquals(
            SheetRuleStats(
                enabled = true,
                runningSince = null,
                statuses = SheetRuleStatus.entries.associateWith { 0L },
                attempts = 0,
                lastAnalyzedAt = null,
                errors = emptyList(),
                budgetDay = TODAY,
                budgetUsed = 0,
                dailyBudget = 7,
            ),
            statistics.stats(),
        )
    }

    @Test
    fun `sheets count by status with their attempts, the latest verdict and the ten most frequent failures`() {
        sheet(SheetRuleStatus.READY, analyzed = "2032-03-01T10:00:00Z")
        sheet(SheetRuleStatus.INSUFFICIENT, attempts = 1, analyzed = "2032-03-01T12:00:00Z")
        sheet(SheetRuleStatus.PENDING, attempts = 2)
        sheet(SheetRuleStatus.RUNNING)
        val failures = listOf("timeout", "timeout", "timeout", "closed", "protocol", "closed", "protocol") + (9 downTo 1).map { "e0$it" }
        failures.forEach { sheet(SheetRuleStatus.FAILED, attempts = 3, error = it) }
        jdbc.update(
            "UPDATE sheet_rule_state SET running_since = ?, budget_day = DATE '2032-03-02', budget_used = 4",
            Timestamp.from(RUNNING_SINCE),
        )

        val stats = statistics.stats()

        assertEquals(
            mapOf(
                SheetRuleStatus.PENDING to 1L,
                SheetRuleStatus.RUNNING to 1L,
                SheetRuleStatus.READY to 1L,
                SheetRuleStatus.INSUFFICIENT to 1L,
                SheetRuleStatus.FAILED to 16L,
            ),
            stats.statuses,
        )
        assertEquals(51, stats.attempts)
        assertEquals(Instant.parse("2032-03-01T12:00:00Z"), stats.lastAnalyzedAt)
        // Ties go by the code.
        assertEquals(
            listOf(SheetRuleErrorCount("timeout", 3), SheetRuleErrorCount("closed", 2), SheetRuleErrorCount("protocol", 2)) +
                (1..7).map { SheetRuleErrorCount("e0$it", 1) },
            stats.errors,
        )
        assertEquals(RUNNING_SINCE, stats.runningSince)
        assertEquals(TODAY to 4, stats.budgetDay to stats.budgetUsed)
    }

    @Test
    fun `the budget of an earlier Moscow day is not today's`() {
        jdbc.update("UPDATE sheet_rule_state SET budget_day = DATE '2032-03-01', budget_used = 5")

        val stats = statistics.stats()

        assertEquals(TODAY to 0, stats.budgetDay to stats.budgetUsed)
    }

    private fun sheet(status: SheetRuleStatus, attempts: Int = 0, analyzed: String? = null, error: String? = null) {
        val fingerprint = analyzed?.let { "a".repeat(64) }
        jdbc.update(
            """
            INSERT INTO sheet_rules (sheet_id, status, rule, fingerprint, model, attempts, last_error, analyzed_at, updated_at)
            VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)
            """,
            "synthetic-sheet-${nextSheet++}",
            status.name,
            if (status == SheetRuleStatus.READY) "{}" else null,
            fingerprint,
            analyzed?.let { "synthetic-model" },
            attempts,
            error,
            analyzed?.let { Timestamp.from(Instant.parse(it)) },
            Timestamp.from(Instant.parse("2032-03-01T00:00:00Z")),
        )
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.parse("2032-03-02")
        val RUNNING_SINCE: Instant = Instant.parse("2032-03-01T21:00:00Z")
    }
}
