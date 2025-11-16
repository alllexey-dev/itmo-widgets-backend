package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.model.FreeSignEntryStatus
import dev.alllexey.itmowidgets.backend.model.SportFreeSignEntity
import dev.alllexey.itmowidgets.backend.repositories.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.core.model.SportFreeSignEntry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class SportFreeSignService(
    private val queueRepository: SportFreeSignEntryRepository,
    private val userService: UserService,
    private val sportLessonService: SportLessonService
) {


    @Transactional
    fun removeEntryById(userId: UUID, entryId: Long) {
        val entry = queueRepository.findById(entryId)
            .orElseThrow { RuntimeException("Entry with id $entryId not found") }

        if (entry.user.id != userId) {
            throw PermissionDeniedException("User $userId is not allowed to delete entry $entryId")
        }

        queueRepository.delete(entry)
    }

    @Transactional
    fun addToQueue(userId: UUID, lessonId: Long): SportFreeSignEntry {
        val user = userService.findUserById(userId)
        val lesson = sportLessonService.findLessonById(lessonId)

        if (queueRepository.findByUserAndLessonAndStatus(user, lesson, FreeSignEntryStatus.WAITING) != null) {
            throw BusinessRuleException("User is already in the queue for this lesson")
        }

        val newEntry = SportFreeSignEntity(user = user, lesson = lesson)
        queueRepository.save(newEntry)

        return getEntry(userId, lessonId)
            ?: throw IllegalStateException("Could not retrieve queue entry status after creation")
    }

    @Transactional
    fun removeFromQueue(userId: UUID, lessonId: Long) {
        val user = userService.findUserById(userId)
        val lesson = sportLessonService.findLessonById(lessonId)
        val queueEntry = queueRepository.findByUserAndLessonAndStatus(user, lesson, FreeSignEntryStatus.WAITING)

        queueEntry?.let {
            queueRepository.delete(it)
        }
    }

    @Transactional(readOnly = true)
    fun getEntry(userId: UUID, lessonId: Long): SportFreeSignEntry? {
        val user = userService.findUserById(userId)
        val lesson = sportLessonService.findLessonById(lessonId)

        val userEntry = queueRepository.findByUserAndLessonAndStatus(user, lesson, FreeSignEntryStatus.WAITING) ?: return null
        if (userEntry.status != FreeSignEntryStatus.WAITING) return null
        val waitingList = queueRepository.findByLessonIdAndStatusOrderByCreatedAt(lessonId, FreeSignEntryStatus.WAITING)

        val position = waitingList.indexOf(userEntry) + 1
        val total = waitingList.size

        return SportFreeSignEntry(
            id = userEntry.id!!,
            lessonId = lessonId,
            position = position,
            total = total
        )
    }

    @Transactional(readOnly = true)
    fun getAllEntriesForUser(userId: UUID): List<SportFreeSignEntry> {
        val user = userService.findUserById(userId)

        val userEntries = queueRepository.findByUserAndStatusOrderByCreatedAt(user, FreeSignEntryStatus.WAITING)
        if (userEntries.isEmpty()) {
            return emptyList()
        }

        val lessonIds = userEntries.map { it.lesson.id }
        val allWaitingEntries = queueRepository.findByLessonIdInAndStatusOrderByCreatedAt(lessonIds, FreeSignEntryStatus.WAITING)
        val waitingListsByLessonId = allWaitingEntries.groupBy { it.lesson.id }

        return userEntries.map { userEntry ->
            val lessonId = userEntry.lesson.id
            val fullWaitingList = waitingListsByLessonId[lessonId] ?: emptyList()

            val position = fullWaitingList.indexOf(userEntry) + 1
            val total = fullWaitingList.size

            SportFreeSignEntry(
                id = userEntry.id!!,
                lessonId = lessonId,
                position = position,
                total = total
            )
        }
    }
}