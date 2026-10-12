package dev.alllexey.itmowidgets.backend.feature.agent.service

import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentScope
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentTokenEntity
import dev.alllexey.itmowidgets.backend.feature.agent.persistence.AgentTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Whether a user's agent takes events of a scope now: a token with the scope polled within the liveness window, and for
 * `sport` its last poll said `itmo=ok`, so attempts never go to an agent that cannot book.
 */
@Service
class AgentReachability(
    private val tokens: AgentTokenRepository,
    private val access: AgentAccessService,
    private val config: AgentConfig,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun isLive(userId: UUID, scope: AgentScope): Boolean =
        tokens.findPolledSince(userId, clock.instant().minus(config.liveness)).any { isLive(it, scope) } && access.isEnabled(userId)

    /** For lists: polled within the window, for any of its scopes. */
    fun isLive(token: AgentTokenEntity): Boolean = token.revokedAt == null &&
        token.lastPollAt?.isAfter(clock.instant().minus(config.liveness)) == true

    private fun isLive(token: AgentTokenEntity, scope: AgentScope): Boolean =
        isLive(token) && scope in token.scopeSet && (scope != AgentScope.SPORT || token.itmoSessionOk)
}
