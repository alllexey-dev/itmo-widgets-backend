package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRoleRepository
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import org.springframework.stereotype.Service
import java.util.UUID

/** Roles are checked in services; ADMIN includes every moderator permission. */
@Service
class AdminAccess(private val roles: UserRoleRepository) {
    fun rolesOf(userId: UUID): Set<UserRole> = roles.findRolesOf(userId).toSet()

    fun requireModerator(userId: UUID) {
        if (!roles.existsByUserIdAndRole(userId, UserRole.MODERATOR) && !roles.existsByUserIdAndRole(userId, UserRole.ADMIN)) {
            throw PermissionDeniedException("Moderator role required")
        }
    }

    fun requireAdmin(userId: UUID) {
        if (!roles.existsByUserIdAndRole(userId, UserRole.ADMIN)) throw PermissionDeniedException("Admin role required")
    }
}
