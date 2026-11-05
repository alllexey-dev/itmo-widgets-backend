package dev.alllexey.itmowidgets.backend.feature.sheets.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * AI rules of open Google Sheets through the host's `sheet-analyzer` on a unix socket (docs/ops/sheet-rules.md).
 * [dailyBudget] counts `analyze` calls per Moscow day; [userRequestsPerHour] limits the analyses one user starts.
 * [model] only labels stored rules: the analyzer picks the model itself. Nothing here is a secret.
 */
@ConfigurationProperties("itmowidgets.sheet-rules")
data class SheetRulesConfig(
    val enabled: Boolean = false,
    val socketPath: String = "",
    val model: String = "claude-sonnet-5-5",
    val dailyBudget: Int = 0,
    val userRequestsPerHour: Int = 5,
    val staleAfter: Duration = Duration.ofDays(1),
    val maxAttempts: Int = 3,
    val fingerprintTimeout: Duration = Duration.ofSeconds(120),
    val analyzeTimeout: Duration = Duration.ofSeconds(480),
) {
    init {
        require(MODEL.matches(model)) { "Sheet rules model must be a model id" }
        require(userRequestsPerHour in 1..1000) { "Sheet rules requests per user and hour must be between 1 and 1000" }
        require(staleAfter >= Duration.ofMinutes(1)) { "Sheet rules staleness must be at least a minute" }
        require(maxAttempts in 1..10) { "Sheet rules attempts must be between 1 and 10" }
        require(fingerprintTimeout > Duration.ZERO && fingerprintTimeout <= Duration.ofMinutes(5)) {
            "Sheet analyzer fingerprint timeout must be positive and at most 5 minutes"
        }
        // The analyzer gives the model 420 s; a shorter wait would drop answers that are still on their way. Both
        // timeouts together stay within the job's lease (SheetRuleStore.STALE_LEASE).
        require(analyzeTimeout > ANALYZER_LIMIT && analyzeTimeout <= Duration.ofMinutes(20)) {
            "Sheet analyzer analyze timeout must be above 420 s and at most 20 minutes"
        }
        if (enabled) {
            require(socketPath.startsWith("/")) { "Sheet analyzer socket path must be absolute" }
            require(dailyBudget in 1..10_000) { "Sheet rules daily budget must be between 1 and 10000" }
        }
    }

    private companion object {
        val MODEL = Regex("^[a-z0-9][a-z0-9.-]{2,99}$")
        val ANALYZER_LIMIT: Duration = Duration.ofSeconds(420)
    }
}
