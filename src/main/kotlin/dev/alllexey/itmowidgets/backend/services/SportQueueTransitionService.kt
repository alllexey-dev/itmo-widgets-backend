package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.SportNotificationIntent
import dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate
import dev.alllexey.itmowidgets.backend.dto.SportQueueKind
import dev.alllexey.itmowidgets.backend.model.SportAutoSignEntity
import dev.alllexey.itmowidgets.backend.model.SportFreeSignEntity
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.model.SportPredictionSnapshot
import dev.alllexey.itmowidgets.backend.model.SportLesson.Companion.toDto
import dev.alllexey.itmowidgets.backend.repositories.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportLessonRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.backend.repositories.UserSportLessonRepository
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus.Companion.notifiableStatuses
import dev.alllexey.itmowidgets.core.model.fcm.impl.SportAutoSignLessonsPayload
import dev.alllexey.itmowidgets.core.model.fcm.impl.SportFreeSignLessonsPayload
import java.time.Clock
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Only IDs cross scheduler boundaries; every write reloads state after the owner lock. */
@Service
class SportQueueTransitionService(
    private val autoRepository: SportAutoSignEntryRepository,
    private val freeRepository: SportFreeSignEntryRepository,
    private val lessonRepository: SportLessonRepository,
    private val userRepository: UserRepository,
    private val userSportLessonRepository: UserSportLessonRepository,
    private val clock: Clock,
) {
    @Transactional
    fun prepareAutoNotification(
        candidate: SportQueueCandidate,
        lessonId: Long,
        bindUnresolved: Boolean,
    ): SportNotificationIntent? {
        if (userRepository.lockById(candidate.userId) == null) return null
        val entry = autoRepository.findById(candidate.entryId).orElse(null) ?: return null
        if (entry.user.id != candidate.userId || entry.isCancelled || entry.status !in notifiableStatuses) return null
        if (bindUnresolved) {
            if (entry.status != QueueEntryStatus.WAITING || entry.realLesson != null) return null
        } else if (entry.realLesson?.id != lessonId) return null
        val lesson = lessonRepository.findById(lessonId).orElse(null) ?: return null
        if (!SportQueueRules.matches(entry.prediction, lesson)) return null
        val now = Instant.now(clock)
        if (userSportLessonRepository.existsByUserIdAndLessonId(candidate.userId, lessonId)) {
            entry.realLesson = lesson
            entry.status = QueueEntryStatus.SATISFIED
            entry.satisfiedAt = now
            return null
        }
        if (!lesson.end.toInstant().isAfter(now) || !entry.prediction.end.plusWeeks(2).toInstant().isAfter(now)) {
            entry.realLesson = lesson
            expire(entry, now)
            return null
        }
        if (!canReserve(entry.lastNotifiedAt, entry.notificationAttempts, entry.maxNotificationAttempts, now)) return null

        entry.realLesson = lesson
        entry.notificationAttempts++
        entry.firstNotifiedAt = entry.firstNotifiedAt ?: now
        entry.lastNotifiedAt = now
        entry.status = notificationStatus(entry.notificationAttempts, entry.maxNotificationAttempts)
        return SportNotificationIntent(
            SportQueueKind.AUTO, candidate.entryId, candidate.userId, lessonId, entry.notificationAttempts,
            SportAutoSignLessonsPayload(listOf(lesson.toDto())),
        )
    }

    @Transactional
    fun prepareFreeNotification(candidate: SportQueueCandidate, lessonId: Long): SportNotificationIntent? {
        if (userRepository.lockById(candidate.userId) == null) return null
        val entry = freeRepository.findById(candidate.entryId).orElse(null) ?: return null
        if (entry.user.id != candidate.userId || entry.lesson.id != lessonId || entry.isCancelled ||
            entry.status !in notifiableStatuses
        ) return null
        val now = Instant.now(clock)
        if (!SportQueueRules.freeDeadline(entry).isAfter(now)) {
            expire(entry, now)
            return null
        }
        if (!canReserve(entry.lastNotifiedAt, entry.notificationAttempts, entry.maxNotificationAttempts, now)) return null

        entry.notificationAttempts++
        entry.firstNotifiedAt = entry.firstNotifiedAt ?: now
        entry.lastNotifiedAt = now
        entry.status = notificationStatus(entry.notificationAttempts, entry.maxNotificationAttempts)
        return SportNotificationIntent(
            SportQueueKind.FREE, candidate.entryId, candidate.userId, lessonId, entry.notificationAttempts,
            SportFreeSignLessonsPayload(listOf(entry.lesson.toDto())),
        )
    }

    @Transactional
    fun expireAutoEntry(candidate: SportQueueCandidate) {
        if (userRepository.lockById(candidate.userId) == null) return
        val entry = autoRepository.findById(candidate.entryId).orElse(null) ?: return
        if (entry.user.id != candidate.userId || entry.isCancelled || entry.status !in notifiableStatuses) return
        val now = Instant.now(clock)
        if (!entry.prediction.end.plusWeeks(2).toInstant().isAfter(now)) expire(entry, now)
    }

    @Transactional
    fun expireFreeEntry(candidate: SportQueueCandidate) {
        if (userRepository.lockById(candidate.userId) == null) return
        val entry = freeRepository.findById(candidate.entryId).orElse(null) ?: return
        if (entry.user.id != candidate.userId || entry.isCancelled || entry.status !in notifiableStatuses) return
        val now = Instant.now(clock)
        if (!SportQueueRules.freeDeadline(entry).isAfter(now)) expire(entry, now)
    }

    /** A separate proxy call after reservation commit; no managed state leaves this read. */
    @Transactional(readOnly = true)
    fun isIntentCurrent(intent: SportNotificationIntent): Boolean {
        if (intent.attemptNumber <= 0) return false
        val now = Instant.now(clock)
        return when (intent.kind) {
            SportQueueKind.AUTO -> {
                val entry = autoRepository.findById(intent.entryId).orElse(null) ?: return false
                val lesson = entry.realLesson ?: return false
                entry.user.id == intent.userId && !entry.isCancelled && lesson.id == intent.lessonId &&
                    isReservedAttempt(entry.status, entry.notificationAttempts, entry.maxNotificationAttempts, intent) &&
                    SportQueueRules.matches(entry.prediction, lesson) &&
                    !userSportLessonRepository.existsByUserIdAndLessonId(intent.userId, intent.lessonId) &&
                    lesson.end.toInstant().isAfter(now) && entry.prediction.end.plusWeeks(2).toInstant().isAfter(now)
            }
            SportQueueKind.FREE -> {
                val entry = freeRepository.findById(intent.entryId).orElse(null) ?: return false
                entry.user.id == intent.userId && !entry.isCancelled && entry.lesson.id == intent.lessonId &&
                    isReservedAttempt(entry.status, entry.notificationAttempts, entry.maxNotificationAttempts, intent) &&
                    SportQueueRules.freeDeadline(entry).isAfter(now)
            }
        }
    }

    private fun canReserve(lastAttempt: Instant?, attempts: Int, maximum: Int, now: Instant): Boolean =
        attempts < maximum && (lastAttempt == null || !lastAttempt.plusSeconds(DEBOUNCE_SECONDS).isAfter(now))

    private fun notificationStatus(attempts: Int, maximum: Int): QueueEntryStatus =
        if (attempts >= maximum) QueueEntryStatus.GAVE_UP_NOTIFYING else QueueEntryStatus.NOTIFIED

    private fun isReservedAttempt(
        status: QueueEntryStatus,
        attempts: Int,
        maximum: Int,
        intent: SportNotificationIntent,
    ): Boolean = attempts == intent.attemptNumber && (
        status == QueueEntryStatus.NOTIFIED || (status == QueueEntryStatus.GAVE_UP_NOTIFYING && attempts == maximum)
    )

    private fun expire(entry: SportAutoSignEntity, now: Instant) {
        entry.status = QueueEntryStatus.EXPIRED
        entry.expiredAt = now
    }

    private fun expire(entry: SportFreeSignEntity, now: Instant) {
        entry.status = QueueEntryStatus.EXPIRED
        entry.expiredAt = now
    }

    companion object {
        private const val DEBOUNCE_SECONDS = 15 * 60L
    }
}

internal object SportQueueRules {
    fun matches(prediction: SportPredictionSnapshot, target: SportLesson): Boolean =
        prediction.sectionId == target.section.id && prediction.teacherIsu == target.teacher.isu &&
            canPredictLocation(prediction.buildingId, prediction.roomId) &&
            canPredictLocation(target.buildingId, target.roomId) &&
            (prediction.roomId == -1L || prediction.buildingId == target.buildingId) &&
            prediction.roomId == target.roomId && prediction.sectionLevel == target.sectionLevel &&
            prediction.lessonLevel == target.lessonLevel && prediction.typeId == target.typeId &&
            prediction.timeSlotId == target.timeSlot.id && prediction.start.isEqual(target.start.minusWeeks(2)) &&
            prediction.end.isEqual(target.end.minusWeeks(2))

    /** Unknown/off-site filter categories cannot prove a venue; room -1 explicitly denotes online. */
    fun canPredictLocation(buildingId: Long?, roomId: Long): Boolean =
        if (roomId == -1L) buildingId == null || buildingId == -1L
        else buildingId != null && buildingId > 0L && roomId > 0L

    /** Non-force stops one hour before start; force remains eligible strictly before lesson end. */
    fun freeDeadline(entry: SportFreeSignEntity): Instant =
        (if (entry.forceSign) entry.lesson.end else entry.lesson.start.minusHours(1)).toInstant()
}
