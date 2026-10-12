package dev.alllexey.itmowidgets.backend.feature.agent.service

import dev.alllexey.itmowidgets.backend.feature.agent.persistence.AgentEventStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** The beta switch: the feature is on for a user when `itmowidgets.agent.enabled` holds and an admin let the user in. */
@Service
class AgentAccessService(private val store: AgentEventStore, private val config: AgentConfig, private val clock: Clock) {
    @Transactional(readOnly = true)
    fun isEnabled(userId: UUID): Boolean = config.enabled && store.hasAccess(userId)

    @Transactional(readOnly = true)
    fun isGranted(userId: UUID): Boolean = store.hasAccess(userId)

    /** Idempotent; returns true when access changed. Tokens are kept either way. */
    @Transactional
    fun setGranted(userId: UUID, granted: Boolean): Boolean =
        if (granted) store.grantAccess(userId, clock.instant()) else store.revokeAccess(userId)
}
