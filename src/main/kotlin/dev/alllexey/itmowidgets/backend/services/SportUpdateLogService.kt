package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.model.SportUpdateLog
import dev.alllexey.itmowidgets.backend.model.SportUpdateOutcome
import dev.alllexey.itmowidgets.backend.repositories.SportUpdateLogRepository
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class SportUpdateLogService(
    private val logs: SportUpdateLogRepository,
    private val clock: Clock,
) {
    /** Called after a failed catalog transaction has rolled back, not from inside its catch block. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFailure(durationMillis: Long, receivedLessons: Int, category: SportUpdateErrorCategory) {
        logs.saveAndFlush(SportUpdateLog(
            updateTimestamp = clock.instant(), outcome = SportUpdateOutcome.FAILED,
            durationMillis = durationMillis, receivedLessons = receivedLessons,
            newLessonsAdded = 0, updatedLessons = 0, skippedLessons = 0, errorCategory = category,
        ))
    }
}
