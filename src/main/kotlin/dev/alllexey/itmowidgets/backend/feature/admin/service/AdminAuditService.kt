package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.model.AdminAuditEntity
import dev.alllexey.itmowidgets.backend.feature.admin.persistence.AdminAuditRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

enum class AdminAuditAction {
    ROLE_GRANTED,
    ROLE_REVOKED,
    MODERATION_SETTINGS_CHANGED,
    APP_VERSION_CHANGED,
    SPORT_SETTINGS_CHANGED,
    REVIEWS_SYNC_STARTED,
    SERVICE_CREDENTIAL_REPLACED,
    AI_SUMMARIES_RUN_STARTED,
    AI_SUMMARY_HIDDEN,
    AI_SUMMARY_SHOWN,
    AI_SUMMARY_REGENERATION_REQUESTED,
    AGENT_ACCESS_GRANTED,
    AGENT_ACCESS_REVOKED,
}

/** Insert-only record of admin changes, written in the transaction of the change itself; [AdminAuditPageService] reads it. */
@Service
class AdminAuditService(private val audit: AdminAuditRepository, private val clock: Clock) {
    /** [details] is short plain text, never a payload, token or JSON. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(actorId: UUID, action: AdminAuditAction, target: String, details: String?) {
        audit.save(
            AdminAuditEntity(
                actorId = actorId,
                action = action.name,
                target = target.take(TARGET_LENGTH),
                details = details?.take(DETAILS_LENGTH),
                createdAt = clock.instant(),
            ),
        )
    }

    private companion object {
        const val TARGET_LENGTH = 200
        const val DETAILS_LENGTH = 1000
    }
}
