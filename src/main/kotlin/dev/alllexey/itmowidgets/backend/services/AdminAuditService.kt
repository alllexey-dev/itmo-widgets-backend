package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.AdminAuditEntry
import dev.alllexey.itmowidgets.backend.dto.AdminPage
import dev.alllexey.itmowidgets.backend.model.AdminAuditEntity
import dev.alllexey.itmowidgets.backend.repositories.AdminAuditRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

enum class AdminAuditAction { ROLE_GRANTED, ROLE_REVOKED, MODERATION_SETTINGS_CHANGED, APP_VERSION_CHANGED }

/** Insert-only record of admin changes, written in the transaction of the change itself. */
@Service
class AdminAuditService(
    private val audit: AdminAuditRepository,
    private val access: AdminAccess,
    private val summaries: AdminUserSummaries,
    private val clock: Clock,
) {
    /** [details] is short plain text, never a payload, token or JSON. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(actorId: UUID, action: AdminAuditAction, target: String, details: String?) {
        audit.save(AdminAuditEntity(actorId = actorId, action = action.name, target = target.take(TARGET_LENGTH),
            details = details?.take(DETAILS_LENGTH), createdAt = clock.instant()))
    }

    @Transactional(readOnly = true)
    fun page(adminId: UUID, page: Int, size: Int): AdminPage<AdminAuditEntry> {
        access.requireAdmin(adminId)
        val result = audit.findPage(AdminPage.request(page, size))
        val actors = summaries.of(result.content.map { it.actorId })
        return AdminPage.of(result, result.content.map { entry ->
            val actor = actors.getValue(entry.actorId)
            AdminAuditEntry(entry.id, entry.action, entry.target, entry.details, entry.createdAt, actor.isu, actor.name)
        })
    }

    private companion object {
        const val TARGET_LENGTH = 200
        const val DETAILS_LENGTH = 1000
    }
}
