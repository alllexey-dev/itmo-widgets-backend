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
class ModerationService(
    private val cases: ModerationCaseRepository,
    private val decisions: ModerationDecisionRepository,
    private val users: UserRepository,
    private val access: ModeratorAccess,
    private val restrictions: RestrictionService,
    private val targets: ModerationTargets,
    private val clock: Clock,
) {
    @Transactional
    fun lock(targetType: ModerationTargetType) { cases.lockTargetType(targetType.name) }

    @Transactional
    fun openCase(targetType: ModerationTargetType, targetId: UUID, reason: ModerationCaseReason): ModerationCaseEntity {
        lock(targetType)
        return cases.findOpen(targetType, targetId) ?: cases.save(ModerationCaseEntity(
            targetType = targetType, targetId = targetId, reason = reason, openedAt = clock.instant()))
    }

    /** Records an automatic approval as a resolved case with a POLICY decision and no moderator. */
    @Transactional
    fun approveByPolicy(targetType: ModerationTargetType, targetId: UUID): ModerationDecisionEntity {
        lock(targetType)
        val now = clock.instant()
        val case = cases.save(ModerationCaseEntity(targetType = targetType, targetId = targetId,
            status = ModerationCaseStatus.RESOLVED, reason = ModerationCaseReason.SUBMISSION, openedAt = now, resolvedAt = now))
        return decisions.save(ModerationDecisionEntity(case = case, moderator = null, action = ModerationAction.APPROVE,
            createdAt = now, actor = ModerationActor.POLICY))
    }

    @Transactional
    fun withdraw(targetType: ModerationTargetType, targetId: UUID) {
        lock(targetType)
        val case = cases.findOpen(targetType, targetId) ?: return
        case.status = ModerationCaseStatus.WITHDRAWN
        case.resolvedAt = clock.instant()
        cases.save(case)
    }

    @Transactional(readOnly = true)
    fun cases(moderatorId: UUID, status: ModerationCaseStatus): List<ModerationCase> {
        access.require(moderatorId)
        return cases.findAllByStatusOrderByOpenedAt(status).map { toDto(it, moderatorId) }
    }

    @Transactional
    fun decide(moderatorId: UUID, caseId: UUID, request: ModerationDecisionRequest): ModerationCase {
        access.require(moderatorId)
        validate(request)
        // Lock all currently supported types in a stable order before loading managed case state.
        ModerationTargetType.entries.forEach(::lock)
        cases.lockById(caseId) ?: throw NotFoundException("Moderation case not found")
        val case = cases.findById(caseId).orElseThrow { NotFoundException("Moderation case not found") }
        if (case.status != ModerationCaseStatus.OPEN &&
            !(case.status == ModerationCaseStatus.RESOLVED && request.action == ModerationAction.RESTORE)) {
            throw BusinessRuleException("Moderation case is already closed")
        }
        val moderator = users.findById(moderatorId).orElseThrow { NotFoundException("User not found") }
        val target = targets.forType(case.targetType)
        val decision = decisions.save(ModerationDecisionEntity(case = case, moderator = moderator,
            action = request.action, note = request.note?.trim()?.takeIf { it.isNotEmpty() },
            restrictionCapability = request.restriction?.capability, restrictionDays = request.restriction?.days,
            createdAt = clock.instant()))
        if (request.action == ModerationAction.RESTRICT_USER) {
            val restriction = request.restriction!!
            val owner = users.findById(target.ownerId(case.targetId)).orElseThrow { NotFoundException("User not found") }
            restrictions.restrict(decision, owner, restriction.capability, restriction.days, decision.note ?: "Community rules violation")
        }
        target.apply(request.action, case.targetId, decision)
        if (request.action !in setOf(ModerationAction.RESTRICT_USER, ModerationAction.HIDE_ALL_BY_USER)) {
            case.status = ModerationCaseStatus.RESOLVED
            case.resolvedAt = clock.instant()
            cases.save(case)
        }
        return toDto(case, moderatorId)
    }

    private fun validate(request: ModerationDecisionRequest) {
        if (request.note != null && request.note.length > 500 ||
            (request.action == ModerationAction.RESTRICT_USER) != (request.restriction != null) ||
            request.restriction?.days?.let { it <= 0 } == true) {
            throw InvalidRequestDataException("Invalid moderation decision")
        }
    }

    private fun describe(case: ModerationCaseEntity, viewerId: UUID): ModerationCaseTarget? = try {
        targets.forType(case.targetType).describe(case.targetId, viewerId)
    } catch (missing: NotFoundException) {
        if (case.status == ModerationCaseStatus.OPEN) throw missing
        null
    }

    private fun toDto(case: ModerationCaseEntity, viewerId: UUID) = ModerationCase(case.id, case.targetType, case.status,
        case.reason, case.openedAt, describe(case, viewerId),
        decisions.findAllByCaseIdOrderByCreatedAt(case.id).map { it.toDto() })
}
