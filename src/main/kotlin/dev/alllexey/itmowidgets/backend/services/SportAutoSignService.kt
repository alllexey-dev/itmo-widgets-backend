package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.model.SportAutoSignEntity
import dev.alllexey.itmowidgets.backend.repositories.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import dev.alllexey.itmowidgets.core.model.SportAutoSignEntry
import dev.alllexey.itmowidgets.core.model.SportAutoSignLimits
import dev.alllexey.itmowidgets.core.model.SportAutoSignQueue
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.*

@Service
class SportAutoSignService(
    private val queueRepository: SportAutoSignEntryRepository,
    private val userService: UserService,
    private val sportLessonService: SportLessonService
) {

    @Transactional(readOnly = true)
    fun getLimits(userId: UUID): SportAutoSignLimits {
        val user = userService.findUserById(userId)
        val userLimit = user.autoSignLimit

        val now = Instant.now()
        val cutoff = now.minus(30, ChronoUnit.DAYS)

        val used = queueRepository.countActiveEntriesInRollingWindow(user, cutoff)
        val available = (userLimit - used).coerceAtLeast(0)

        val nextAvailableAt = if (available > 0) {
            OffsetDateTime.now()
        } else {
            val oldest = queueRepository.findOldestActiveEntry(user, cutoff).firstOrNull()
            val timestamp = oldest?.notifiedAt ?: oldest?.createdAt ?: now
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
        val user = userService.findUserById(userId)
        val limits = getLimits(userId)

        if (limits.available <= 0) {
            throw BusinessRuleException("Auto-sign limit reached. Next available slot at ${limits.nextAvailableAt}")
        }

        val prototype = sportLessonService.findLessonById(prototypeLessonId)
        if (queueRepository.findByUserAndPrototypeLessonAndStatus(user, prototype, QueueEntryStatus.WAITING) != null) {
            throw BusinessRuleException("Already subscribed to auto-sign for this lesson")
        }

        val entity = SportAutoSignEntity(
            user = user,
            prototypeLesson = prototype,
            realLesson = null
        )

        queueRepository.save(entity)

        return toModel(entity)
    }

    @Transactional(readOnly = true)
    fun getMyEntries(userId: UUID): List<SportAutoSignEntry> {
        val user = userService.findUserById(userId)

        val cutoffDate = OffsetDateTime.now().minusWeeks(4)

        val userEntries = queueRepository.findRecentByUser(user, cutoffDate)
        if (userEntries.isEmpty()) {
            return emptyList()
        }

        val waitingPrototypeIds = userEntries
            .filter { it.status == QueueEntryStatus.WAITING }
            .map { it.prototypeLesson.id }
            .distinct()

        val waitingListsByLessonId = if (waitingPrototypeIds.isNotEmpty()) {
            queueRepository.findByPrototypeLessonIdInAndStatusOrderByCreatedAt(waitingPrototypeIds, QueueEntryStatus.WAITING)
                .groupBy { it.prototypeLesson.id }
        } else {
            emptyMap()
        }

        return userEntries.map { userEntry ->
            val lessonId = userEntry.prototypeLesson.id
            var position = 0
            var total = 0

            if (userEntry.status == QueueEntryStatus.WAITING) {
                val fullWaitingList = waitingListsByLessonId[lessonId] ?: emptyList()
                position = fullWaitingList.indexOfFirst { it.id == userEntry.id } + 1
                total = fullWaitingList.size
            }

            toModel(userEntry, position, total)
        }
    }

    @Transactional
    fun deleteEntry(userId: UUID, entryId: Long) {
        val entry = findQueueEntryById(entryId)
        if (entry.user.id != userId) {
            throw PermissionDeniedException("User $userId is not allowed to delete entry $entryId")
        }

        if (entry.status != QueueEntryStatus.WAITING) {
            throw BusinessRuleException("Entry is not waiting")
        }
        queueRepository.delete(entry)
    }

    @Transactional
    fun markSatisfied(userId: UUID, entryId: Long) {
        val entry = findQueueEntryById(entryId)
        if (entry.user.id != userId) {
            throw PermissionDeniedException("User $userId is not allowed to update entry $entryId")
        }

        when (entry.status) {
            QueueEntryStatus.SATISFIED -> return
            QueueEntryStatus.WAITING, QueueEntryStatus.NOTIFIED -> {
                entry.status = QueueEntryStatus.SATISFIED
                entry.satisfiedAt = Instant.now()
                queueRepository.save(entry)
            }
            QueueEntryStatus.EXPIRED -> throw BusinessRuleException("Cannot satisfy an expired entry")
        }
    }

    @Transactional(readOnly = true)
    fun getCurrentQueues(): List<SportAutoSignQueue> = queueRepository.findAllCurrentQueues()

    fun findQueueEntryById(entryId: Long): SportAutoSignEntity {
        return queueRepository.findById(entryId)
            .orElseThrow { RuntimeException("Entry with id $entryId not found") }
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
            status = entity.status,
            createdAt = entity.createdAt.atZone(ZoneOffset.systemDefault()).toOffsetDateTime(),
            notifiedAt = entity.notifiedAt?.atZone(ZoneOffset.systemDefault())?.toOffsetDateTime(),
            prototypeLessonData = sportLessonService.toBasicData(entity.prototypeLesson),
            realLessonData = entity.realLesson?.let { sportLessonService.toBasicData(it) }
        )
    }

    private fun toModel(entity: SportAutoSignEntity): SportAutoSignEntry {
        val lessonId = entity.prototypeLesson.id
        var position = 0
        var total = 0

        if (entity.status == QueueEntryStatus.WAITING) {
            val waitingList = queueRepository.findByPrototypeLessonIdAndStatusOrderByCreatedAt(lessonId, QueueEntryStatus.WAITING)
            position = waitingList.indexOfFirst { it.id == entity.id } + 1
            total = waitingList.size
        }

        return toModel(entity, position, total)
    }
}