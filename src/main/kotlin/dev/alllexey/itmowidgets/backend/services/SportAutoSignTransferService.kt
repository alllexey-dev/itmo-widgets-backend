package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.SportFreeSignTransferResult
import dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate
import dev.alllexey.itmowidgets.backend.model.SportQueueRules
import dev.alllexey.itmowidgets.backend.repositories.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportLessonRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import java.time.Clock
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SportAutoSignTransferService(
    private val autoRepository: SportAutoSignEntryRepository,
    private val lessonRepository: SportLessonRepository,
    private val userRepository: UserRepository,
    private val freeSignService: SportFreeSignService,
    private val clock: Clock,
) {
    /** Null is an obsolete candidate; genuine persistence errors roll back only this owner's transfer. */
    @Transactional
    fun transferEntry(candidate: SportQueueCandidate, lessonId: Long): SportFreeSignTransferResult? {
        if (userRepository.lockById(candidate.userId) == null) return null
        val entry = autoRepository.findById(candidate.entryId).orElse(null) ?: return null
        if (entry.user.id != candidate.userId || entry.isCancelled ||
            entry.status != QueueEntryStatus.WAITING || entry.realLesson != null
        ) return null
        val lesson = lessonRepository.findById(lessonId).orElse(null) ?: return null
        if (!SportQueueRules.matches(entry.prediction, lesson)) return null

        val result = freeSignService.ensureEntryForTransfer(candidate.userId, lessonId)
        val now = Instant.now(clock)
        entry.realLesson = lesson
        if (result == SportFreeSignTransferResult.ALREADY_SATISFIED) {
            entry.status = QueueEntryStatus.SATISFIED
            entry.satisfiedAt = now
        } else {
            entry.status = QueueEntryStatus.EXPIRED
            entry.expiredAt = now
        }
        return result
    }
}
