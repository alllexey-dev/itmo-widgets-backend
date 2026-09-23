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
class ModerationSettingsService(
    private val repository: ModerationSettingRepository,
    private val cases: ModerationCaseRepository,
    private val moderation: ModerationService,
    private val access: ModeratorAccess,
    private val clock: Clock,
    private val targets: ModerationTargets,
) {
    private data class Cached(val value: ModerationPolicy, val expiresAt: Instant)
    private val cache = java.util.concurrent.ConcurrentHashMap<ModerationTargetType, Cached>()

    @Transactional(readOnly = true)
    fun policy(targetType: ModerationTargetType): ModerationPolicy {
        // Mutations must observe the committed policy after taking the type lock, never a stale UI cache.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive() &&
            !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) return readPolicy(targetType)
        val now = clock.instant()
        cache[targetType]?.takeIf { it.expiresAt > now }?.let { return it.value }
        return readPolicy(targetType).also { cache[targetType] = Cached(it, now.plusSeconds(30)) }
    }

    @Transactional(readOnly = true)
    fun settings(moderatorId: UUID): ModerationSettings {
        access.require(moderatorId)
        return ModerationSettings(ModerationTargetType.entries.associateWith(::readPolicy))
    }

    @Transactional
    fun update(moderatorId: UUID, request: ModerationSettings): ModerationSettings {
        access.require(moderatorId)
        if (request.policies.keys != ModerationTargetType.entries.toSet()) {
            throw InvalidRequestDataException("All moderation target policies are required")
        }
        request.policies.values.forEach { it.validate() }
        ModerationTargetType.entries.forEach(moderation::lock)
        for ((type, next) in request.policies) {
            val before = readPolicy(type)
            val previous = before.toKeys(type)
            next.toKeys(type).filter { (key, value) -> previous[key] != value }.forEach { (key, value) ->
                repository.upsert(key, value, clock.instant(), moderatorId)
            }
            if (before.premoderation && !next.premoderation) {
                cases.findAllByStatusOrderByOpenedAt(ModerationCaseStatus.OPEN)
                    .filter { it.targetType == type && it.reason == ModerationCaseReason.SUBMISSION && targets.forType(type).canAutoApprove(it.targetId) }
                    .forEach { moderation.decide(moderatorId, it.id, ModerationDecisionRequest(
                        ModerationAction.APPROVE, note = "Premoderation disabled")) }
            }
        }
        // Never publish an uncommitted policy in the process-wide cache, even after rollback.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                object : org.springframework.transaction.support.TransactionSynchronization {
                    override fun afterCompletion(status: Int) { cache.clear() }
                })
        } else cache.clear()
        return request
    }

    private fun readPolicy(type: ModerationTargetType): ModerationPolicy = ModerationPolicy.fromKeys(type,
        repository.findAllByKeyStartingWith("${type.name}.").associate { it.key to it.value })
}
