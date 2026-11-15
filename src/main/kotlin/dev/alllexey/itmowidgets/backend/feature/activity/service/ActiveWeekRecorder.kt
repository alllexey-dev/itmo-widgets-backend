package dev.alllexey.itmowidgets.backend.feature.activity.service

import dev.alllexey.itmowidgets.backend.feature.activity.model.ActivityCalendar
import dev.alllexey.itmowidgets.backend.feature.activity.persistence.ActiveWeekRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Records `user_active_weeks`: one row per account and Moscow calendar week in which it made an authenticated request.
 * It remembers the accounts already recorded for the current week only, so a request reaches the database once per
 * account and week: the first one inserts the row, the rest end at the in-memory check. A new week starts with the
 * accounts the table already holds for it, so a restart writes nothing twice; the week before is forgotten.
 */
@Service
class ActiveWeekRecorder(private val weeks: ActiveWeekRepository, private val clock: Clock) {
    @Volatile private var current: Recorded? = null

    /** Not transactional: most calls end at the in-memory check without a database connection. */
    fun record(userId: UUID) {
        val week = ActivityCalendar.weekOf(clock.instant())
        val recorded = recordedIn(week)
        if (recorded == null) {
            // A request that read the clock just before a new week began, after another one started it.
            weeks.record(userId, week)
            return
        }
        if (userId in recorded.users) return
        weeks.record(userId, week)
        // Only after the write, so a failed one is retried; dropping the set costs one no-op write per active account.
        if (recorded.users.size >= MAX_TRACKED) recorded.users.clear()
        recorded.users.add(userId)
    }

    /** The accounts recorded for [week], loaded when it becomes the current week; null for a week already past. */
    private fun recordedIn(week: LocalDate): Recorded? {
        current?.takeIf { it.week >= week }?.let { known -> return known.takeIf { known.week == week } }
        synchronized(this) {
            current?.takeIf { it.week >= week }?.let { known -> return known.takeIf { known.week == week } }
            val users = ConcurrentHashMap.newKeySet<UUID>().apply { addAll(weeks.usersIn(week)) }
            return Recorded(week, users).also { current = it }
        }
    }

    private class Recorded(val week: LocalDate, val users: MutableSet<UUID>)

    private companion object {
        /** About 4 MB of ids; far above the accounts active in one week. */
        const val MAX_TRACKED = 50_000
    }
}
