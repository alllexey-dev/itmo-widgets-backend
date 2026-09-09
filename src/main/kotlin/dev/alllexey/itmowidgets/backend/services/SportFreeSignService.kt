package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.SportFreeSignTransferResult
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.model.SportFreeSignEntity
import dev.alllexey.itmowidgets.backend.model.SportLesson.Companion.toDto
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.backend.repositories.UserSportLessonRepository
import dev.alllexey.itmowidgets.backend.repositories.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportLessonRepository
import dev.alllexey.itmowidgets.backend.services.SportAutoSignService.Companion.toOffsetDateTime
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus.Companion.notifiableStatuses
import dev.alllexey.itmowidgets.core.model.SportFreeSignEntry
import dev.alllexey.itmowidgets.core.model.SportFreeSignQueue
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.*

@Service
class SportFreeSignService(
    private val queueRepository: SportFreeSignEntryRepository,
    private val userService: UserService,
    private val sportLessonService: SportLessonService,
    private val sportLessonRepository: SportLessonRepository,
    private val userRepository: UserRepository,
    private val userSportLessonRepository: UserSportLessonRepository,
    private val clock: Clock,
) {

    @Transactional
    fun sync(user: User, lessonIds: List<Long>) {
        lockOwner(user.id)
        val entries = queueRepository.findRecentByUser(user, cutoffDate())
        val now = Instant.now(clock)
        entries.forEach { entry ->
            if (entry.lesson.id in lessonIds) {
                if (entry.status in notifiableStatuses || entry.status == QueueEntryStatus.GAVE_UP_NOTIFYING) {
                    entry.status = QueueEntryStatus.SATISFIED
                    entry.satisfiedAt = now
                }
            } else {
                if (entry.status == QueueEntryStatus.SATISFIED) {
                    entry.isCancelled = true
                    entry.cancelledAt = now
                }
            }
        }
    }

    @Transactional(readOnly = true)
    fun getUserEntries(userId: UUID): List<SportFreeSignEntry> {
        val user = userService.findUserById(userId)

        val userEntries = queueRepository.findRecentByUser(user, cutoffDate())
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
        lockOwner(userId)
        val currEntry = queueRepository.findNotCancelledEntry(userId, lessonId)
        if (currEntry != null && currEntry.status in notifiableStatuses) return toModel(currEntry)

        val user = userService.findUserById(userId)
        val lesson = sportLessonService.findLessonById(lessonId)
        if (!lesson.end.isAfter(OffsetDateTime.now(clock))) {
            throw BusinessRuleException("Cannot join queue: Lesson has already ended.")
        }
        releaseEntry(currEntry)
        val entity = queueRepository.save(
            SportFreeSignEntity(user = user, lesson = lesson, forceSign = forceSign, createdAt = Instant.now(clock))
        )
        return toModel(entity)
    }

    /** Called inside the transfer transaction; expected outcomes never poison its commit. */
    @Transactional
    fun ensureEntryForTransfer(userId: UUID, lessonId: Long): SportFreeSignTransferResult {
        lockOwner(userId)
        val user = userService.findUserById(userId)
        val lesson = sportLessonService.findLessonById(lessonId)
        val existing = queueRepository.findNotCancelledEntry(userId, lessonId)
        if (existing?.status == QueueEntryStatus.SATISFIED ||
            userSportLessonRepository.existsByUserIdAndLessonId(userId, lessonId)
        ) return SportFreeSignTransferResult.ALREADY_SATISFIED
        if (!lesson.end.isAfter(OffsetDateTime.now(clock))) return SportFreeSignTransferResult.LESSON_ENDED
        if (existing != null && existing.status in notifiableStatuses) return SportFreeSignTransferResult.EXISTING

        releaseEntry(existing)
        queueRepository.save(
            SportFreeSignEntity(user = user, lesson = lesson, forceSign = false, createdAt = Instant.now(clock))
        )
        return SportFreeSignTransferResult.CREATED
    }

    private fun releaseEntry(entry: SportFreeSignEntity?) {
        if (entry == null) return
        entry.isCancelled = true
        entry.cancelledAt = Instant.now(clock)
        // Flush before IDENTITY insertion to release the partial-unique slot.
        queueRepository.flush()
    }

    private fun toModel(entry: SportFreeSignEntity): SportFreeSignEntry {
        val waiting = queueRepository.findAllByLessonsAndStatuses(listOf(entry.lesson.id), notifiableStatuses)
        val position = if (entry.status in notifiableStatuses) waiting.indexOfFirst { it.id == entry.id } + 1 else 0
        return mapEntityToModel(entry, position, waiting.size, entry.lesson)
    }

    @Transactional
    fun cancelEntry(userId: UUID, entryId: Long) {
        lockOwner(userId)
        val entry = findQueueEntryById(entryId)
        if (entry.user.id != userId) throw PermissionDeniedException("Queue entry belongs to another user")
        cancel(entry)
    }

    @Transactional
    fun cancelEntryByLesson(userId: UUID, lessonId: Long) {
        lockOwner(userId)
        queueRepository.findNotCancelledEntry(userId, lessonId)?.let { cancel(it) }
    }

    @Transactional
    fun markEntrySatisfied(userId: UUID, entryId: Long) {
        lockOwner(userId)
        val entry = findQueueEntryById(entryId)
        if (entry.user.id != userId) throw PermissionDeniedException("Queue entry belongs to another user")
        satisfy(entry)
    }

    @Transactional
    fun markEntrySatisfiedByLesson(userId: UUID, lessonId: Long) {
        lockOwner(userId)
        queueRepository.findNotCancelledEntry(userId, lessonId)?.let { satisfy(it) }
    }

    private fun cancel(entry: SportFreeSignEntity) {
        if (entry.isCancelled) return
        entry.cancelledAt = Instant.now(clock)
        entry.isCancelled = true
    }

    private fun satisfy(entry: SportFreeSignEntity) {
        if (entry.isCancelled || entry.status == QueueEntryStatus.SATISFIED ||
            entry.status == QueueEntryStatus.EXPIRED
        ) return
        entry.status = QueueEntryStatus.SATISFIED
        entry.satisfiedAt = Instant.now(clock)
    }

    private fun lockOwner(userId: UUID) {
        userRepository.lockById(userId) ?: throw NotFoundException("User not found")
    }

    @Transactional(readOnly = true)
    fun getCurrentQueues(): List<SportFreeSignQueue> {
        return queueRepository.findAllCurrentQueues(OffsetDateTime.now(clock))
    }

    fun findQueueEntryById(entryId: Long): SportFreeSignEntity {
        return queueRepository.findById(entryId)
            .orElseThrow { NotFoundException("Entry with id $entryId not found") }
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
            targetLesson = lesson.toDto(),
            forceSign = entity.forceSign,
            notificationAttempts = entity.notificationAttempts,
            maxNotificationAttempts = entity.maxNotificationAttempts,
        )
    }

    private fun cutoffDate(): OffsetDateTime = OffsetDateTime.now(clock)
}
