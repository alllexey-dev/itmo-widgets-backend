package dev.alllexey.itmowidgets.backend.feature.activity.service

import dev.alllexey.itmowidgets.backend.feature.activity.model.ActivePeople
import dev.alllexey.itmowidgets.backend.feature.activity.model.ActivityCalendar
import dev.alllexey.itmowidgets.backend.feature.activity.persistence.PeopleDailyRepository
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Writes yesterday's (Moscow) `stats_people_daily` row from [UserLastSeen] as of now, next to the `stats_clients_daily` job. The
 * windows end at the moment of the run, so a rerun later in the day would count other people: the first row of a day
 * stays and a rerun changes nothing.
 */
@Service
class PeopleDailyJob(private val lastSeen: UserLastSeen, private val days: PeopleDailyRepository, private val clock: Clock) {
    @Scheduled(cron = "0 20 0 * * *", zone = "Europe/Moscow")
    fun nightly() {
        try {
            val now = clock.instant()
            days.insert(ActivityCalendar.dayOf(now).minusDays(1), ActivePeople.of(lastSeen.all().values, now))
        } catch (error: Exception) {
            // Spring's default scheduled-task logger includes unsafe exception messages and causes.
            logger.error("People statistics job failed: {}", SafeDiagnostics.describe(error), error)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PeopleDailyJob::class.java)
    }
}
