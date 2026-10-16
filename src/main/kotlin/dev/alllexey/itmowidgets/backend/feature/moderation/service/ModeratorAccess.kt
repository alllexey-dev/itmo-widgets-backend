package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import org.springframework.stereotype.Service
import java.util.UUID

/** Moderation callers keep this entry point; the rule itself lives in [AdminAccess]. */
@Service
class ModeratorAccess(private val access: AdminAccess) {
    fun require(userId: UUID) = access.requireModerator(userId)

    fun rolesOf(userId: UUID): Set<UserRole> = access.rolesOf(userId)

    /** Admins decide cases about their own content; moderators wait for someone else. */
    fun maySelfModerate(userId: UUID): Boolean = UserRole.ADMIN in rolesOf(userId)
}
