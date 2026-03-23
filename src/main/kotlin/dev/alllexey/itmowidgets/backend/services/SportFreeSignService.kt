package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.model.SportFreeSignEntity
import dev.alllexey.itmowidgets.backend.model.SportLesson.Companion.toBasicData
import dev.alllexey.itmowidgets.backend.repositories.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportLessonRepository
import dev.alllexey.itmowidgets.backend.services.SportAutoSignService.Companion.toOffsetDateTime
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus.Companion.notifiableStatuses
import dev.alllexey.itmowidgets.core.model.SportFreeSignEntry
import dev.alllexey.itmowidgets.core.model.SportFreeSignQueue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.util.*

@Service
class SportFreeSignService(
    private val queueRepository: SportFreeSignEntryRepository,
    private val userService: UserService,
    private val sportLessonService: SportLessonService,
    private val sportLessonRepository: SportLessonRepository
) {

    @Transactional(readOnly = true)
    fun getUserEntries(userId: UUID): List<SportFreeSignEntry> {
        val user = userService.findUserById(userId)

        val cutoffDate = OffsetDateTime.now().minusWeeks(2)
        val userEntries = queueRepository.findRecentByUser(user, cutoffDate)
        if (userEntries.isEmpty()) {
            return emptyList()
        }

        val lessonIds = userEntries.map { it.lesson.id }.distinct()
        val lessons = sportLessonRepository.findAllById(lessonIds).associateBy { it.id }

        val waitingLessonIds = userEntries
            .filter { it.status in notifiableStatuses }
            .map { it.lesson.id }
            .distinct()

        val queuesByLessonId = queueRepository.findAllByLessonsAndStatuses(waitingLessonIds, notifiableStatuses)
                .groupBy { it.lesson.id }

        return userEntries.map { userEntry ->
            val lessonId = userEntry.lesson.id
            var position = 0
            var total = 0

            if (userEntry.status in notifiableStatuses) {
                val fullWaitingList = queuesByLessonId[lessonId] ?: emptyList()
                position = fullWaitingList.indexOfFirst { it.id == userEntry.id } + 1
                total = fullWaitingList.size
            }

            mapEntityToModel(userEntry, position, total, lessons[lessonId]!!)
        }
    }

    @Transactional
    fun createEntry(userId: UUID, lessonId: Long, forceSign: Boolean): SportFreeSignEntry {
        val user = userService.findUserById(userId)
        val lesson = sportLessonService.findLessonById(lessonId)

        if (lesson.end.isBefore(OffsetDateTime.now())) {
            throw BusinessRuleException("Cannot join queue: Lesson has already ended.")
        }

        val currEntry = queueRepository.findNotCancelledEntry(userId, lessonId)
        if (currEntry?.status in notifiableStatuses) {
            throw BusinessRuleException("Already subscribed to free-sign for this lesson")
        }

        currEntry?.isCancelled = true
        currEntry?.cancelledAt = Instant.now()

        val newEntry = SportFreeSignEntity(user = user, lesson = lesson, forceSign = forceSign)
        queueRepository.save(newEntry)

        val waitingList = queueRepository.findAllByLessonsAndStatuses(listOf(lessonId), notifiableStatuses)
        val position = waitingList.indexOfFirst { it.user.id == userId } + 1

        return mapEntityToModel(newEntry, position, waitingList.size, lesson)
    }

    @Transactional
    fun cancelEntry(userId: UUID, entryId: Long) {
        val entry = findQueueEntryById(entryId)
        if (entry.user.id != userId) {
            throw PermissionDeniedException("User $userId is not allowed to delete entry $entryId")
        }

        if (entry.isCancelled) {
            throw BusinessRuleException("Entry is already cancelled")
        }

        entry.cancelledAt = Instant.now()
        entry.isCancelled = true
    }

    @Transactional
    fun cancelEntryByLesson(userId: UUID, lessonId: Long) {
        val entry = queueRepository.findNotCancelledEntry(userId, lessonId)
            ?: throw BusinessRuleException("User has no active entries for lesson $lessonId")

        if (entry.isCancelled) {
            throw BusinessRuleException("Entry is already cancelled")
        }

        entry.cancelledAt = Instant.now()
        entry.isCancelled = true
    }

    @Transactional
    fun markEntrySatisfied(userId: UUID, entryId: Long) {
        val entry = findQueueEntryById(entryId)
        if (entry.user.id != userId) {
            throw PermissionDeniedException("User $userId is not allowed to update entry $entryId")
        }

        if (entry.isCancelled) {
            throw BusinessRuleException("Can't satisfy a cancelled entry")
        }

        when (entry.status) {
            QueueEntryStatus.SATISFIED -> return
            QueueEntryStatus.WAITING, QueueEntryStatus.NOTIFIED, QueueEntryStatus.GAVE_UP_NOTIFYING -> {
                entry.status = QueueEntryStatus.SATISFIED
                val now = Instant.now()
                entry.satisfiedAt = now
                entry.isCancelled = true
                entry.cancelledAt = now
                logger.info("Entry $entry is marked as satisfied")
            }

            QueueEntryStatus.EXPIRED -> throw BusinessRuleException("Can't satisfy an expired entry")
        }
    }

    @Transactional
    fun markEntrySatisfiedByLesson(userId: UUID, lessonId: Long) {
        val entry = queueRepository.findNotCancelledEntry(userId, lessonId)
            ?: throw BusinessRuleException("User has no active entries for lesson $lessonId")

        when (entry.status) {
            QueueEntryStatus.SATISFIED -> return
            QueueEntryStatus.WAITING, QueueEntryStatus.NOTIFIED, QueueEntryStatus.GAVE_UP_NOTIFYING -> {
                entry.status = QueueEntryStatus.SATISFIED
                val now = Instant.now()
                entry.satisfiedAt = now
                entry.isCancelled = true
                entry.cancelledAt = now
                logger.info("Entry $entry is marked as satisfied by lesson")
            }

            QueueEntryStatus.EXPIRED -> throw BusinessRuleException("Can't satisfy an expired entry")
        }
    }

    @Transactional(readOnly = true)
    fun getCurrentQueues(): List<SportFreeSignQueue> {
        return queueRepository.findAllCurrentQueues(OffsetDateTime.now())
    }

    fun findQueueEntryById(entryId: Long): SportFreeSignEntity {
        return queueRepository.findById(entryId)
            .orElseThrow { RuntimeException("Entry with id $entryId not found") }
    }

    private fun mapEntityToModel(
        entity: SportFreeSignEntity,
        position: Int,
        total: Int,
        lesson: dev.alllexey.itmowidgets.backend.model.SportLesson
    ): SportFreeSignEntry {
        return SportFreeSignEntry(
            id = entity.id!!,
            lessonId = lesson.id,
            position = position,
            total = total,
            isCancelled = entity.isCancelled,
            status = entity.status,
            createdAt = entity.createdAt.toOffsetDateTime(),
            firstNotifiedAt = entity.firstNotifiedAt?.toOffsetDateTime(),
            lastNotifiedAt = entity.lastNotifiedAt?.toOffsetDateTime(),
            cancelledAt = entity.cancelledAt?.toOffsetDateTime(),
            satisfiedAt = entity.satisfiedAt?.toOffsetDateTime(),
            expiredAt = entity.expiredAt?.toOffsetDateTime(),
            targetLesson = lesson.toBasicData(),
            forceSign = entity.forceSign,
            notificationAttempts = entity.notificationAttempts,
            maxNotificationAttempts = entity.maxNotificationAttempts,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SportFreeSignService::class.java)
    }
}