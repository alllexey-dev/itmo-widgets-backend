package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.*
import dev.alllexey.itmowidgets.backend.model.*
import dev.alllexey.itmowidgets.backend.repositories.*
import dev.alllexey.itmowidgets.backend.exceptions.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class ModeratorAccess(private val roles: UserRoleRepository) {
    fun require(userId: UUID) {
        if (!roles.existsByUserIdAndRole(userId, UserRole.MODERATOR)) {
            throw PermissionDeniedException("Moderator role required")
        }
    }
}
