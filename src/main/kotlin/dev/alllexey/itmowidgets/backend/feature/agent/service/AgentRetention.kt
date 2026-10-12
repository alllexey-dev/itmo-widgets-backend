package dev.alllexey.itmowidgets.backend.feature.agent.service

import dev.alllexey.itmowidgets.backend.feature.agent.persistence.AgentEventStore
import dev.alllexey.itmowidgets.backend.feature.agent.persistence.AgentTokenRepository
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

/** Hourly: expired and surplus events go, idle tokens are revoked, long-revoked tokens are deleted. Idempotent on any instance. */
@Service
class AgentRetention(
    private val store: AgentEventStore,
    private val tokens: AgentTokenRepository,
    private val config: AgentConfig,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @Scheduled(cron = "0 25 * * * *", zone = "Europe/Moscow")
    fun scheduled() {
        try {
            // A self-call bypasses the proxy, so @Transactional on purge() does not apply here.
            transaction.executeWithoutResult { purge() }
        } catch (error: Exception) {
            // Spring's default scheduled-task logger includes unsafe exception messages and causes.
            logger.error("Agent retention failed: {}", SafeDiagnostics.describe(error), error)
        }
    }

    @Transactional
    fun purge() {
        val now = clock.instant()
        store.deleteExpired(now, AgentEventService.KEEP_PER_USER)
        val idle = tokens.revokeIdle(now.minus(config.idleRevoke), now)
        if (idle > 0) logger.info("Revoked {} agent tokens unused for {}", idle, config.idleRevoke)
        tokens.deleteRevokedBefore(now.minus(REVOKED_RETENTION))
    }

    companion object {
        val REVOKED_RETENTION: Duration = Duration.ofDays(90)
        private val logger = LoggerFactory.getLogger(AgentRetention::class.java)
    }
}
