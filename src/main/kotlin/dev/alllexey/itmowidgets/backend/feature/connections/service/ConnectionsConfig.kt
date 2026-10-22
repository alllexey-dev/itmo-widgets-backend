package dev.alllexey.itmowidgets.backend.feature.connections.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Connected clients (docs/contracts/connections.md). [agentEnabled] and [mcpEnabled] switch a client type off for
 * everyone; agents also need an admin's per-user grant. An agent is live while it polled within [liveness]. A user has
 * at most [maxPerClient] live connections of each type. A web connection ends after [webIdle] without requests, and
 * reaches admin and moderation routes only within [adminMaxAge] after its last phone approval.
 */
@ConfigurationProperties("itmowidgets.connections")
data class ConnectionsConfig(
    val agentEnabled: Boolean = true,
    val mcpEnabled: Boolean = true,
    val liveness: Duration = Duration.ofMinutes(3),
    val maxPerClient: Int = 5,
    val webIdle: Duration = Duration.ofDays(30),
    val adminMaxAge: Duration = Duration.ofDays(7),
) {
    init {
        require(liveness > Duration.ofMinutes(1)) { "Connection liveness must be longer than the one-minute poll step" }
        require(maxPerClient > 0) { "Connections max-per-client must be positive" }
        require(webIdle > Duration.ZERO) { "Connections web-idle must be positive" }
        require(adminMaxAge > Duration.ZERO) { "Connections admin-max-age must be positive" }
    }
}
