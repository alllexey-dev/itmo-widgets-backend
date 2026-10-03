package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import org.springframework.stereotype.Service
import java.util.UUID

/** Moderation callers keep this entry point; the rule itself lives in [AdminAccess]. */
@Service
class ModeratorAccess(private val access: AdminAccess) {
    fun require(userId: UUID) = access.requireModerator(userId)
}
