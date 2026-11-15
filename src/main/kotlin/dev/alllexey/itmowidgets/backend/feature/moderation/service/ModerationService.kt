package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.moderation.model.DecisionPrior
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationActor
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionRevertEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationCaseRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationDecisionRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationDecisionRevertRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCase
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCaseTarget
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationDecisionRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.toDto
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.platform.error.ServiceException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class ModerationService(
    private val cases: ModerationCaseRepository,
    private val decisions: ModerationDecisionRepository,
    private val reverts: ModerationDecisionRevertRepository,
    private val users: UserRepository,
    private val access: ModeratorAccess,
    private val restrictions: RestrictionService,
    private val targets: ModerationTargets,
    private val config: ModerationConfig,
    private val clock: Clock,
) {
    @Transactional
    fun lock(targetType: ModerationTargetType) {
        cases.lockTargetType(targetType.name)
    }

    @Transactional
    fun openCase(targetType: ModerationTargetType, targetId: UUID, reason: ModerationCaseReason): ModerationCaseEntity {
        lock(targetType)
        return cases.findOpen(
            targetType,
            targetId,
        ) ?: cases.save(ModerationCaseEntity(targetType = targetType, targetId = targetId, reason = reason, openedAt = clock.instant()))
    }

    /** Records an automatic approval as a resolved case with a POLICY decision and no moderator. */
    @Transactional
    fun approveByPolicy(targetType: ModerationTargetType, targetId: UUID): ModerationDecisionEntity {
        lock(targetType)
        val now = clock.instant()
        val case = cases.save(
            ModerationCaseEntity(
                targetType = targetType,
                targetId = targetId,
                status = ModerationCaseStatus.RESOLVED,
                reason = ModerationCaseReason.SUBMISSION,
                openedAt = now,
                resolvedAt = now,
            ),
        )
        return decisions.save(
            ModerationDecisionEntity(
                case = case,
                moderator = null,
                action = ModerationAction.APPROVE,
                createdAt = now,
                actor = ModerationActor.POLICY,
            ),
        )
    }

    /**
     * Resolves the open SUBMISSION case of the target with a POLICY approval and [note]. False when no such case is
     * open (a moderator decided first) or the target refuses an automatic approval.
     */
    @Transactional
    fun approveOpenByPolicy(targetType: ModerationTargetType, targetId: UUID, note: String): Boolean {
        lock(targetType)
        val case = cases.findOpen(targetType, targetId)?.takeIf { it.reason == ModerationCaseReason.SUBMISSION } ?: return false
        val target = targets.forType(targetType)
        if (!target.canAutoApprove(targetId)) return false
        val now = clock.instant()
        val decision = decisions.save(
            ModerationDecisionEntity(
                case = case,
                moderator = null,
                action = ModerationAction.APPROVE,
                note = note,
                createdAt = now,
                actor = ModerationActor.POLICY,
            ),
        )
        target.apply(ModerationAction.APPROVE, targetId, decision)
        case.status = ModerationCaseStatus.RESOLVED
        case.resolvedAt = now
        cases.save(case)
        return true
    }

    /** Withdraws the open case of the target, if any; [by] is the HIDE_ALL_BY_USER decision whose sweep withdraws it. */
    @Transactional
    fun withdraw(targetType: ModerationTargetType, targetId: UUID, by: ModerationDecisionEntity? = null) {
        lock(targetType)
        val case = cases.findOpen(targetType, targetId) ?: return
        case.status = ModerationCaseStatus.WITHDRAWN
        case.resolvedAt = clock.instant()
        case.withdrawnBy = by
        cases.save(case)
    }

    @Transactional(readOnly = true)
    fun cases(moderatorId: UUID, status: ModerationCaseStatus): List<ModerationCase> {
        access.require(moderatorId)
        return cases.findAllByStatusOrderByOpenedAt(status).map { toDto(it, moderatorId) }
    }

    @Transactional(readOnly = true)
    fun case(moderatorId: UUID, caseId: UUID): ModerationCase {
        access.require(moderatorId)
        return toDto(cases.findById(caseId).orElseThrow { NotFoundException("Moderation case not found") }, moderatorId)
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
            !(case.status == ModerationCaseStatus.RESOLVED && request.action == ModerationAction.RESTORE)
        ) {
            throw BusinessRuleException("Moderation case is already closed")
        }
        val moderator = users.findById(moderatorId).orElseThrow { NotFoundException("User not found") }
        val target = targets.forType(case.targetType)
        val ownerId = target.ownerId(case.targetId)
        if (ownerId == moderatorId) {
            if (!access.maySelfModerate(moderatorId)) {
                throw PermissionDeniedException("Moderators cannot decide cases about their own content")
            }
            if (request.action == ModerationAction.RESTRICT_USER) throw PermissionDeniedException("An admin cannot restrict themselves")
        }
        if (request.action == ModerationAction.RESTRICT_USER && access.rolesOf(ownerId).isNotEmpty() &&
            UserRole.ADMIN !in access.rolesOf(moderatorId)
        ) {
            throw PermissionDeniedException("Only an admin can restrict a moderator or an admin")
        }
        // A revertible decision records what it replaces, so its revert restores exactly that.
        val prior = if (request.action.revertible) {
            DecisionPrior(target.state(case.targetId) ?: throw NotFoundException("Moderation target not found"), case.resolvedAt)
        } else {
            null
        }
        val decision = decisions.save(
            ModerationDecisionEntity(
                case = case,
                moderator = moderator,
                action = request.action,
                note = request.note?.trim()?.takeIf { it.isNotEmpty() },
                restrictionCapability = request.restriction?.capability,
                restrictionDays = request.restriction?.days,
                createdAt = clock.instant(),
                prior = prior,
            ),
        )
        if (request.action == ModerationAction.RESTRICT_USER) {
            val restriction = request.restriction!!
            val owner = users.findById(ownerId).orElseThrow { NotFoundException("User not found") }
            restrictions.restrict(decision, owner, restriction.capability, restriction.days, decision.note ?: "Community rules violation")
        }
        target.apply(request.action, case.targetId, decision)
        if (request.action.resolvesCase) {
            case.status = ModerationCaseStatus.RESOLVED
            case.resolvedAt = clock.instant()
            cases.save(case)
        }
        return toDto(case, moderatorId)
    }

    /**
     * Reverts [decisionId], the latest decision of case [caseId], under the locks of a decision: its target and the
     * reports it dismissed return to what they were, the case to its status and `resolvedAt` before it (an open case
     * keeps its `openedAt`, so it returns to its place in the queue), and a revert row records who undid it when.
     * Every refusal leaves everything as it was.
     */
    @Transactional
    fun revert(callerId: UUID, caseId: UUID, decisionId: UUID): ModerationCase {
        access.require(callerId)
        ModerationTargetType.entries.forEach(::lock)
        cases.lockById(caseId) ?: throw NotFoundException("Moderation case not found")
        val case = cases.findById(caseId).orElseThrow { NotFoundException("Moderation case not found") }
        val history = decisions.findAllByCaseIdOrderByCreatedAtAscIdAsc(caseId)
        val decision = history.firstOrNull { it.id == decisionId } ?: throw NotFoundException("Moderation decision not found")
        revertRefusal(case, history, decision, callerId, reverted = reverts.existsById(decisionId))?.let { throw it }
        val prior = checkNotNull(decision.prior)
        targets.forType(case.targetType).revert(case.targetId, decision)
        case.status = if (prior.caseResolvedAt == null) ModerationCaseStatus.OPEN else ModerationCaseStatus.RESOLVED
        case.resolvedAt = prior.caseResolvedAt
        cases.save(case)
        val caller = users.findById(callerId).orElseThrow { NotFoundException("User not found") }
        reverts.save(ModerationDecisionRevertEntity(decision.id, caller, clock.instant()))
        return toDto(case, callerId)
    }

    /**
     * Why [callerId] may not revert [decision] now, or null when they may. [history] is the decisions of [case], oldest
     * first. What can be undone and who may undo it come first; the target is read last.
     */
    private fun revertRefusal(
        case: ModerationCaseEntity,
        history: List<ModerationDecisionEntity>,
        decision: ModerationDecisionEntity,
        callerId: UUID,
        reverted: Boolean,
    ): ServiceException? {
        if (decision.actor != ModerationActor.MODERATOR || !decision.action.revertible) {
            return BusinessRuleException("Only an APPROVE, REJECT, HIDE, RESTORE or DISMISS decision of a moderator can be reverted")
        }
        if (decision.moderator?.id != callerId && !access.mayRevertOthers(callerId)) {
            return PermissionDeniedException("Only the deciding moderator or an admin can revert a decision")
        }
        if (reverted) return BusinessRuleException("The decision is already reverted")
        val prior = decision.prior ?: return BusinessRuleException("The decision was taken before decisions could be reverted")
        if (clock.instant().isAfter(revertibleUntil(decision))) return BusinessRuleException("The revert window has passed")
        if (history.last().id != decision.id) return BusinessRuleException("Only the latest decision of a case can be reverted")
        val target = targets.forType(case.targetType)
        val state = target.state(case.targetId) ?: return BusinessRuleException("The target was deleted since the decision")
        if (target.ownerId(case.targetId) == callerId && !access.maySelfModerate(callerId)) {
            return PermissionDeniedException("Moderators cannot decide cases about their own content")
        }
        if (cases.findOpen(case.targetType, case.targetId) != null) return BusinessRuleException("Another case for the target is open")
        if (state != prior.target.after(decision.action, decision)) return BusinessRuleException("The target changed since the decision")
        return null
    }

    private fun revertibleUntil(decision: ModerationDecisionEntity): Instant = decision.createdAt.plus(config.revertWindow)

    private fun validate(request: ModerationDecisionRequest) {
        if ((request.note != null && request.note.length > 500) ||
            (request.action == ModerationAction.RESTRICT_USER) != (request.restriction != null) ||
            request.restriction?.days?.let { it <= 0 } == true
        ) {
            throw InvalidRequestDataException("Invalid moderation decision")
        }
    }

    private fun describe(case: ModerationCaseEntity, viewerId: UUID): ModerationCaseTarget? = try {
        targets.forType(case.targetType).describe(case.targetId, viewerId)
    } catch (missing: NotFoundException) {
        if (case.status == ModerationCaseStatus.OPEN) throw missing
        null
    }

    private fun toDto(case: ModerationCaseEntity, viewerId: UUID): ModerationCase {
        val history = decisions.findAllByCaseIdOrderByCreatedAtAscIdAsc(case.id)
        val revertedAt = if (history.isEmpty()) {
            emptyMap()
        } else {
            reverts.findAllByDecisionIdIn(history.map { it.id }).associate { it.decisionId to it.revertedAt }
        }
        // Only a resolved case can have a revertible decision: it is the latest one, and it resolved the case.
        val revertible = history.lastOrNull()?.takeIf {
            case.status == ModerationCaseStatus.RESOLVED && revertRefusal(case, history, it, viewerId, it.id in revertedAt) == null
        }
        return ModerationCase(
            case.id,
            case.targetType,
            case.status,
            case.reason,
            case.openedAt,
            describe(case, viewerId),
            history.map { it.toDto(revertedAt[it.id], if (it == revertible) revertibleUntil(it) else null) },
        )
    }
}
