package dev.alllexey.itmowidgets.backend.feature.agent.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Self-hosted agents (docs/contracts/agent.md). [enabled] switches the feature off for everyone; per user an admin grants
 * access. A token is live while it polled within [liveness]; it is revoked after [idleRevoke] without requests.
 */
@ConfigurationProperties("itmowidgets.agent")
data class AgentConfig(
    val enabled: Boolean = true,
    val liveness: Duration = Duration.ofMinutes(3),
    val createRecentAuth: Duration = Duration.ofHours(12),
    val maxTokens: Int = 5,
    val idleRevoke: Duration = Duration.ofDays(90),
) {
    init {
        require(liveness > Duration.ofMinutes(1)) { "Agent liveness must be longer than the one-minute poll step" }
        require(createRecentAuth > Duration.ZERO) { "Agent create-recent-auth must be positive" }
        require(maxTokens > 0) { "Agent max-tokens must be positive" }
        require(idleRevoke > Duration.ZERO) { "Agent idle-revoke must be positive" }
    }
}
