package dev.alllexey.itmowidgets.backend.testing

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionRoutePolicy
import dev.alllexey.itmowidgets.backend.platform.security.ConnectionAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.ConnectionDecision
import dev.alllexey.itmowidgets.backend.platform.security.ConnectionResolver
import java.time.Instant
import java.util.UUID

/** Synthetic connections for security slice tests: a resolver over a token map with the production route policy. */
object TestConnections {
    /** Well-formed tokens; the resolver below knows only what a test puts into its map. */
    const val WEB_TOKEN = "iwc_wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww"
    const val AGENT_TOKEN = "iwc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val MCP_TOKEN = "iwc_mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm"

    fun web(userId: UUID, approvedAt: Instant, freshForAdmin: Boolean = true, id: UUID = UUID.randomUUID()) =
        ConnectionAuthentication(userId, id, "web", "Chrome, macOS", setOf("user"), approvedAt, freshForAdmin)

    fun client(userId: UUID, client: ClientType, scopes: Set<ConnectionScope>, name: String = "nas", id: UUID = UUID.randomUUID()) =
        ConnectionAuthentication(userId, id, client.wire, name, scopes.map { it.wire }.toSet(), Instant.EPOCH, freshForAdmin = false)

    /** Resolves the tokens of [connections] at request time, so a test may add one before it calls. */
    fun resolver(connections: Map<String, ConnectionAuthentication>): ConnectionResolver = object : ConnectionResolver {
        override fun resolve(token: String) = connections[token]

        override fun decide(authentication: ConnectionAuthentication, method: String, path: String): ConnectionDecision {
            val client = checkNotNull(ClientType.fromWire(authentication.client))
            val scopes = authentication.scopes.mapNotNull(ConnectionScope::fromWire).toSet()
            return if (ConnectionRoutePolicy.permitted(client, scopes, method, path) !=
                null
            ) {
                ConnectionDecision.ALLOWED
            } else {
                ConnectionDecision.DENIED
            }
        }
    }
}
