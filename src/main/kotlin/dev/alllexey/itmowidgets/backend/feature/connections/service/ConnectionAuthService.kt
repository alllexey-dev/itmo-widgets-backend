package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionRepository
import dev.alllexey.itmowidgets.backend.platform.ratelimit.WindowLimiter
import dev.alllexey.itmowidgets.backend.platform.security.ClientSessions
import dev.alllexey.itmowidgets.backend.platform.security.ConnectionAuthFilter
import dev.alllexey.itmowidgets.backend.platform.security.ConnectionAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.ConnectionDecision
import dev.alllexey.itmowidgets.backend.platform.security.ConnectionResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * Connection tokens per request: resolve (only SHA-256 hashes are stored), the route policy, access and rate limits,
 * and the `client_session` rule of the ITMO.ID filter.
 */
@Service
class ConnectionAuthService(
    private val connections: ConnectionRepository,
    private val access: ClientAccessService,
    private val config: ConnectionsConfig,
    private val clock: Clock,
) : ConnectionResolver,
    ClientSessions {
    private val eventsLimiter = WindowLimiter(EVENTS_PER_MINUTE, Duration.ofMinutes(1), clock)
    private val routesLimiter = WindowLimiter(ROUTES_PER_MINUTE, Duration.ofMinutes(1), clock)

    @Transactional
    override fun resolve(token: String): ConnectionAuthentication? {
        if (!token.startsWith(ConnectionAuthFilter.PREFIX) ||
            !ConnectionTokens.isWellFormed(token.removePrefix(ConnectionAuthFilter.PREFIX))
        ) {
            return null
        }
        val connection = connections.findActiveByHash(ConnectionTokens.sha256(token)) ?: return null
        val now = clock.instant()
        if (connection.client == ClientType.WEB && !(connection.lastUsedAt ?: connection.approvedAt).plus(config.webIdle).isAfter(now)) {
            connection.revokedAt = now
            log.info("Connection {} of user {} revoked after {} unused", connection.id, connection.userId, config.webIdle)
            return null
        }
        connections.touch(connection.id, now, now.minus(LAST_USED_STEP))
        return ConnectionAuthentication(
            connection.userId,
            connection.id,
            connection.client.wire,
            connection.name,
            connection.scopeSet.map { it.wire }.toSet(),
            connection.approvedAt,
            freshForAdmin = connection.approvedAt.plus(config.adminMaxAge).isAfter(now),
        )
    }

    override fun decide(authentication: ConnectionAuthentication, method: String, path: String): ConnectionDecision {
        val client = checkNotNull(ClientType.fromWire(authentication.client))
        val scopes = authentication.scopes.mapNotNull(ConnectionScope::fromWire).toSet()
        val route = ConnectionRoutePolicy.permitted(client, scopes, method, path)
        if (route?.selfRevoke != true && !access.isEnabled(authentication.userId, client)) return ConnectionDecision.DISABLED
        if (route == null) return ConnectionDecision.DENIED
        if (client == ClientType.WEB) return ConnectionDecision.ALLOWED
        val limiter = if (route.events) eventsLimiter else routesLimiter
        return if (limiter.tryAcquire(authentication.connectionId)) ConnectionDecision.ALLOWED else ConnectionDecision.RATE_LIMITED
    }

    @Transactional(readOnly = true)
    override fun isClientSession(sid: String): Boolean = connections.existsSession(sid, clock.instant())

    companion object {
        const val EVENTS_PER_MINUTE = 120
        const val ROUTES_PER_MINUTE = 60
        val LAST_USED_STEP: Duration = Duration.ofMinutes(5)
        private val log = LoggerFactory.getLogger(ConnectionAuthService::class.java)
    }
}
