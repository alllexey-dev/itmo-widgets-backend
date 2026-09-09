package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.model.SportAutoSignEntity
import dev.alllexey.itmowidgets.backend.model.SportLesson.Companion.toDto
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.backend.repositories.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus.Companion.notifiableStatuses
import dev.alllexey.itmowidgets.core.model.SportAutoSignEntry
import dev.alllexey.itmowidgets.core.model.SportAutoSignLimits
import dev.alllexey.itmowidgets.core.model.SportAutoSignQueue
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.*

@Service
class SportAutoSignService(
    private val queueRepository: SportAutoSignEntryRepository,
    private val userService: UserService,
    private val sportLessonService: SportLessonService,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {

    @Transactional
    fun sync(user: User, lessonIds: List<Long>) {
        lockOwner(user.id)
        val entries = queueRepository.findRecentByUser(user, cutoffDate())
        val now = Instant.now(clock)
        entries.forEach { entry ->
            if (entry.realLesson?.id in lessonIds) {
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
    fun getLimits(userId: UUID): SportAutoSignLimits {
        val user = userService.findUserById(userId)
        val userLimit = user.settings.autoSignLimit

        val now = Instant.now(clock)
        val cutoff = now.minus(30, ChronoUnit.DAYS)

        val used = queueRepository.countActiveEntriesInRollingWindow(user, cutoff)
        val available = (userLimit - used).coerceAtLeast(0)

        val nextAvailableAt = if (available > 0) {
            OffsetDateTime.now(clock)
        } else {
            val oldest = queueRepository.findOldestActiveEntry(user, cutoff).firstOrNull()
            val timestamp = oldest?.firstNotifiedAt ?: oldest?.createdAt ?: now
            timestamp.plus(30, ChronoUnit.DAYS).atZone(ZoneOffset.UTC).toOffsetDateTime()
        }

        return SportAutoSignLimits(
            limit = userLimit,
            available = available,
            nextAvailableAt = nextAvailableAt
        )
    }

    @Transactional
    fun createEntry(userId: UUID, prototypeLessonId: Long): SportAutoSignEntry {
        lockOwner(userId)
        val user = userService.findUserById(userId)
        val currEntry = queueRepository.findNotCancelledEntry(userId, prototypeLessonId)
        if (currEntry != null && currEntry.status in notifiableStatuses) return toModel(currEntry)

        val limits = getLimits(userId)
        if (limits.available <= 0) {
            throw BusinessRuleException("Auto-sign limit reached. Next available slot at ${limits.nextAvailableAt}")
        }
        val prototype = sportLessonService.findLessonById(prototypeLessonId)
        val now = Instant.now(clock)
        if (currEntry != null) {
            currEntry.isCancelled = true
            currEntry.cancelledAt = now
            // IDENTITY insertion must observe the partial-unique slot released first.
            queueRepository.flush()
        }
        val entity = queueRepository.save(
            SportAutoSignEntity(user = user, prototypeLesson = prototype, realLesson = null, createdAt = now)
        )
        return toModel(entity)
    }

    @Transactional(readOnly = true)
    fun getUserEntries(userId: UUID): List<SportAutoSignEntry> {
        val user = userService.findUserById(userId)

        val userEntries = queueRepository.findRecentByUser(user, cutoffDate())
        if (userEntries.isEmpty()) {
            return emptyList()
        }

        val prototypeIds = userEntries
            .filter { it.status in notifiableStatuses }
            .map { it.prototypeLesson.id }
            .distinct()

        val queuesByPrototypeId = queueRepository.findAllByPrototypeLessonsAndStatuses(prototypeIds, notifiableStatuses)
            .groupBy { it.prototypeLesson.id }

        return userEntries.map { userEntry ->
            val lessonId = userEntry.prototypeLesson.id
            var position = 0
            var total = 0

            if (userEntry.status in notifiableStatuses) {
                val fullWaitingList = queuesByPrototypeId[lessonId] ?: emptyList()
                position = fullWaitingList.indexOfFirst { it.id == userEntry.id } + 1
                total = fullWaitingList.size
            }

            toModel(userEntry, position, total)
        }
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
        val entries = queueRepository.findAllNotCancelledEntriesByRealLesson(userId, lessonId)
        entries.forEach { entry -> cancel(entry) }
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
        queueRepository.findAllNotCancelledEntriesByRealLesson(userId, lessonId).forEach { satisfy(it) }
    }

    private fun cancel(entry: SportAutoSignEntity) {
        if (entry.isCancelled) return
        entry.cancelledAt = Instant.now(clock)
        entry.isCancelled = true
    }

    private fun satisfy(entry: SportAutoSignEntity) {
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
    fun getCurrentQueues(): List<SportAutoSignQueue> = queueRepository.findAllCurrentQueues()

    fun findQueueEntryById(entryId: Long): SportAutoSignEntity {
        return queueRepository.findById(entryId)
            .orElseThrow { NotFoundException("Entry with id $entryId not found") }
    }

    private fun toModel(
        entity: SportAutoSignEntity,
        position: Int,
        total: Int
    ): SportAutoSignEntry {
        return SportAutoSignEntry(
            id = entity.id!!,
            prototypeLessonId = entity.prototypeLesson.id,
            realLessonId = entity.realLesson?.id,
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
            targetLesson = entity.prototypeLesson.toDto(),
            realLesson = entity.realLesson?.toDto(),
            notificationAttempts = entity.notificationAttempts,
            maxNotificationAttempts = entity.maxNotificationAttempts,
        )
    }

    private fun toModel(entity: SportAutoSignEntity): SportAutoSignEntry {
        val lessonId = entity.prototypeLesson.id
        var position = 0
        var total = 0

        if (entity.status in notifiableStatuses) {
            val waitingList =
                queueRepository.findAllByPrototypeLessonsAndStatuses(listOf(lessonId), notifiableStatuses)
            position = waitingList.indexOfFirst { it.id == entity.id } + 1
            total = waitingList.size
        }

        return toModel(entity, position, total)
    }

    private fun cutoffDate(): OffsetDateTime = OffsetDateTime.now(clock).minusWeeks(2)

    companion object {
        fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
    }
}
