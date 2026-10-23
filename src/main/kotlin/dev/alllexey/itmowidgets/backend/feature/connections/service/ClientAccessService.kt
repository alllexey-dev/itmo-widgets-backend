package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ClientEventStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Whether a client type is on for a user: MCP for everyone while `mcp-enabled` holds, agents behind the beta, the
 * global switch and an admin's grant; the web always.
 */
@Service
class ClientAccessService(private val store: ClientEventStore, private val config: ConnectionsConfig, private val clock: Clock) {
    @Transactional(readOnly = true)
    fun isEnabled(userId: UUID, client: ClientType): Boolean = when (client) {
        ClientType.AGENT -> config.agentEnabled && store.hasAccess(userId)
        ClientType.MCP -> config.mcpEnabled
        ClientType.WEB -> true
    }

    @Transactional(readOnly = true)
    fun isAgentGranted(userId: UUID): Boolean = store.hasAccess(userId)

    /** Idempotent; returns true when access changed. Connections are kept either way. */
    @Transactional
    fun setAgentGranted(userId: UUID, granted: Boolean): Boolean =
        if (granted) store.grantAccess(userId, clock.instant()) else store.revokeAccess(userId)
}
