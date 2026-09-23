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

interface ModerationTarget {
    fun targetType(): ModerationTargetType
    fun ownerId(targetId: UUID): UUID
    fun isReportable(targetId: UUID, reporterId: UUID): Boolean
    fun apply(action: ModerationAction, targetId: UUID, decision: ModerationDecisionEntity)
    fun canAutoApprove(targetId: UUID): Boolean = true
    fun describe(targetId: UUID, viewerId: UUID): ModerationCaseTarget
}

/** Resolve targets only during a request: targets themselves depend on the moderation services. */
@Service
class ModerationTargets(private val targets: org.springframework.beans.factory.ObjectProvider<ModerationTarget>) {
    fun forType(type: ModerationTargetType): ModerationTarget = targets.orderedStream()
        .filter { it.targetType() == type }.findFirst().orElseThrow { InvalidRequestDataException("Unsupported moderation target") }
}
