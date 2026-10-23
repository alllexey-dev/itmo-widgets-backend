package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ClientEventStore
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionRepository
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

/**
 * Hourly: expired and surplus events go; approvals nobody claimed and idle web connections are revoked; revoked
 * connections forget an ITMO.ID session that expired or that the phone ended, and go [REVOKED_RETENTION] after their
 * revocation once they hold none. Idempotent on any instance.
 */
@Service
class ConnectionRetention(
    private val store: ClientEventStore,
    private val connections: ConnectionRepository,
    private val config: ConnectionsConfig,
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
            logger.error("Connection retention failed: {}", SafeDiagnostics.describe(error), error)
        }
    }

    @Transactional
    fun purge() {
        val now = clock.instant()
        store.deleteExpired(now, ClientEventService.KEEP_PER_USER)
        val revoked = connections.revokeAbandoned(now.minus(UNCLAIMED), now.minus(config.webIdle), now)
        if (revoked > 0) logger.info("Revoked {} unclaimed or idle connections", revoked)
        connections.forgetEndedSessions(now)
        connections.deleteRevokedBefore(now.minus(REVOKED_RETENTION))
    }

    companion object {
        /** Longer than any approval's claim window: 10 minutes after the open and the claim grace. */
        val UNCLAIMED: Duration = Duration.ofMinutes(15)
        val REVOKED_RETENTION: Duration = Duration.ofDays(90)
        private val logger = LoggerFactory.getLogger(ConnectionRetention::class.java)
    }
}
