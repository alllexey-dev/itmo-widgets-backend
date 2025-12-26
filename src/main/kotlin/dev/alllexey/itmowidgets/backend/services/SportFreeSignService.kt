package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.model.SportFreeSignEntity
import dev.alllexey.itmowidgets.backend.repositories.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportLessonRepository
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import dev.alllexey.itmowidgets.core.model.SportFreeSignEntry
import dev.alllexey.itmowidgets.core.model.SportFreeSignQueue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

@Service
class SportFreeSignService(
    private val queueRepository: SportFreeSignEntryRepository,
    private val userService: UserService,
    private val sportLessonService: SportLessonService,
    private val sportLessonRepository: SportLessonRepository
) {

    @Transactional(readOnly = true)
    fun getMyEntries(userId: UUID): List<SportFreeSignEntry> {
        val user = userService.findUserById(userId)

        val cutoffDate = OffsetDateTime.now().minusWeeks(2)
        val userEntries = queueRepository.findRecentByUser(user, cutoffDate)
        if (userEntries.isEmpty()) {
            return emptyList()
        }

        val lessonIds = userEntries.map { it.lesson.id }
        val lessons = sportLessonRepository.findAllById(lessonIds).associateBy { it.id }

        val waitingLessonIds = userEntries
            .filter { it.status == QueueEntryStatus.WAITING }
            .map { it.lesson.id }
            .distinct()

        val waitingListsByLessonId = if (waitingLessonIds.isNotEmpty()) {
            queueRepository.findByLessonIdInAndStatusOrderByCreatedAt(waitingLessonIds, QueueEntryStatus.WAITING)
                .groupBy { it.lesson.id }
        } else {
            emptyMap()
        }

        return userEntries.map { userEntry ->
            val lessonId = userEntry.lesson.id
            var position = 0
            var total = 0

            if (userEntry.status == QueueEntryStatus.WAITING) {
                val fullWaitingList = waitingListsByLessonId[lessonId] ?: emptyList()
                position = fullWaitingList.indexOfFirst { it.id == userEntry.id } + 1
                total = fullWaitingList.size
            }

            mapEntityToModel(userEntry, position, total, lessons[lessonId]!!)
        }
    }

    @Transactional
    fun addToQueue(userId: UUID, lessonId: Long, forceSign: Boolean): SportFreeSignEntry {
        val user = userService.findUserById(userId)
        val lesson = sportLessonService.findLessonById(lessonId)

        if (lesson.end.isBefore(OffsetDateTime.now())) {
            throw BusinessRuleException("Cannot join queue: Lesson has already ended.")
        }

        if (queueRepository.findByUserAndLessonAndStatus(user, lesson, QueueEntryStatus.WAITING) != null) {
            throw BusinessRuleException("User is already in the queue for this lesson")
        }

        val newEntry = SportFreeSignEntity(user = user, lesson = lesson, forceSign = forceSign)
        queueRepository.save(newEntry)

        val waitingList = queueRepository.findByLessonIdAndStatusOrderByCreatedAt(lessonId, QueueEntryStatus.WAITING)
        val position = waitingList.indexOfFirst { it.user.id == userId } + 1

        return mapEntityToModel(newEntry, position, waitingList.size, lesson)
    }

    @Transactional
    fun removeEntryById(userId: UUID, entryId: Long) {
        val entry = findQueueEntryById(entryId)
        if (entry.user.id != userId) {
            throw PermissionDeniedException("User $userId is not allowed to delete entry $entryId")
        }

        if (entry.status != QueueEntryStatus.WAITING) {
            throw BusinessRuleException("Cannot delete entry with status ${entry.status}")
        }

        queueRepository.delete(entry)
    }

    @Transactional
    fun markEntrySatisfied(userId: UUID, entryId: Long) {
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
                logger.warn("Entry $entry is marked as satisfied")
            }
            QueueEntryStatus.EXPIRED -> throw BusinessRuleException("Cannot satisfy an expired entry")
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
            status = entity.status,
            createdAt = entity.createdAt.atZone(ZoneOffset.systemDefault()).toOffsetDateTime(),
            notifiedAt = entity.notifiedAt?.atZone(ZoneOffset.systemDefault())?.toOffsetDateTime(),
            lessonData = sportLessonService.toBasicData(lesson),
            forceSign = entity.forceSign
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SportFreeSignService::class.java)
    }
}