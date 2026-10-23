package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionEntity
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.model.ItmoStatus
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Whether a user's agent takes events of a scope now: an agent with the scope polled within the liveness window, and
 * for `sport` its last poll said `itmo=ok`, so attempts never go to an agent that cannot book. MCP servers and the web
 * are never live: they take no events.
 */
@Service
class ClientReachability(
    private val connections: ConnectionRepository,
    private val access: ClientAccessService,
    private val config: ConnectionsConfig,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun isLive(userId: UUID, scope: ConnectionScope): Boolean =
        connections.findAgentsPolledSince(userId, clock.instant().minus(config.liveness)).any { isLive(it, scope) } &&
            access.isEnabled(userId, ClientType.AGENT)

    /** For lists: an agent that polled within the window, for any of its scopes. */
    fun isLive(connection: ConnectionEntity): Boolean = connection.client == ClientType.AGENT && connection.revokedAt == null &&
        connection.lastPollAt?.isAfter(clock.instant().minus(config.liveness)) == true

    private fun isLive(connection: ConnectionEntity, scope: ConnectionScope): Boolean =
        isLive(connection) && scope in connection.scopeSet && (scope != ConnectionScope.SPORT || connection.itmoStatus == ItmoStatus.OK)
}
