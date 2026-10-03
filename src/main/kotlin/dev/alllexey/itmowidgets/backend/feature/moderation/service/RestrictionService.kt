package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.moderation.model.UserRestrictionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.UserRestrictionRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.web.UserRestriction
import dev.alllexey.itmowidgets.backend.feature.moderation.web.toDto
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.RestrictedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class RestrictionService(
    private val restrictions: UserRestrictionRepository,
    private val users: UserRepository,
    private val access: ModeratorAccess,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun activeFor(userId: UUID): List<UserRestriction> = active(userId).map { it.toDto() }

    @Transactional(readOnly = true)
    fun require(userId: UUID, capability: RestrictionCapability) {
        val restriction = active(userId).firstOrNull { it.capability == capability || it.capability == RestrictionCapability.ALL }
            ?: return
        throw RestrictedException(restriction.capability, restriction.expiresAt, "Action restricted by moderation")
    }

    @Transactional
    fun restrict(decision: ModerationDecisionEntity, target: User, capability: RestrictionCapability, days: Int?, reason: String) {
        if (days != null && days <= 0 || reason.isBlank() || reason.length > 500) {
            throw InvalidRequestDataException("Invalid restriction")
        }
        val now = clock.instant()
        restrictions.save(UserRestrictionEntity(user = target, capability = capability, decision = decision,
            reason = reason, startsAt = now, expiresAt = days?.let { now.plusSeconds(it.toLong() * 86400) }))
    }

    @Transactional
    fun revoke(restrictionId: UUID, moderatorId: UUID) {
        access.require(moderatorId)
        val restriction = restrictions.findById(restrictionId).orElseThrow { NotFoundException("Restriction not found") }
        if (restriction.revokedAt != null) return
        restriction.revokedAt = clock.instant()
        restriction.revokedBy = users.findById(moderatorId).orElseThrow { NotFoundException("User not found") }
        restrictions.save(restriction)
    }

    @Transactional(readOnly = true)
    fun forUser(moderatorId: UUID, isu: Int): List<UserRestriction> {
        access.require(moderatorId)
        val user = users.findByIsu(isu) ?: throw NotFoundException("User not found")
        return activeFor(user.id)
    }

    private fun active(userId: UUID): List<UserRestrictionEntity> {
        val now = clock.instant()
        return restrictions.findActive(userId, now).filter {
            it.revokedAt == null && it.startsAt <= now && (it.expiresAt?.let { expires -> expires > now } ?: true)
        }
    }
}
