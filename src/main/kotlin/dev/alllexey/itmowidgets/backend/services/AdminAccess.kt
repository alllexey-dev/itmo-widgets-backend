package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.model.UserRole
import dev.alllexey.itmowidgets.backend.repositories.UserRoleRepository
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
