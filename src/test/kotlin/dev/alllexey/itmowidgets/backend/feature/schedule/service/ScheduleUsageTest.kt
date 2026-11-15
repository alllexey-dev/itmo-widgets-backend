package dev.alllexey.itmowidgets.backend.feature.schedule.service

import dev.alllexey.itmowidgets.backend.feature.schedule.persistence.ScheduleStatisticsRepository
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserStatisticsRepository
import dev.alllexey.itmowidgets.backend.feature.users.service.UserStatistics
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.testing.insertUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The schedule row of the feature use on real PostgreSQL with synthetic lessons dated in 2032. */
@Import(ScheduleUsage::class, ScheduleStatisticsRepository::class, UserStatistics::class, UserStatisticsRepository::class)
class ScheduleUsageTest @Autowired constructor(private val usage: ScheduleUsage, private val jdbc: JdbcTemplate) :
    PostgreSqlRepositoryTest() {
    private var nextPair = 984_600L

    @Test
    fun `users with a lesson dated in the period use the schedule, with no events and not as their own action`() {
        val period = StatsPeriod(LocalDate.parse("2032-03-01"), LocalDate.parse("2032-03-03"))
        val first = jdbc.insertUser(984_600, createdAt = Instant.parse("2032-01-01T00:00:00Z"))
        val second = jdbc.insertUser(984_601, createdAt = Instant.parse("2032-01-01T00:00:00Z"))
        jdbc.insertUser(984_602, createdAt = Instant.parse("2032-01-01T00:00:00Z"))
        lesson(984_600, "2032-03-01")
        lesson(984_600, "2032-03-03")
        lesson(984_601, "2032-03-03")
        lesson(984_602, "2032-02-29")
        lesson(984_602, "2032-03-04")
        // A lesson of an ISU without an account names nobody.
        lesson(984_603, "2032-03-02")
        // Nor does a placeholder: its negative ISU never matches a lesson's.
        jdbc.update("INSERT INTO users (id, isu, name) VALUES (?, -984603, 'Deleted account')", UUID.randomUUID())

        assertEquals(FeatureUsage(null, setOf(first, second)), usage.usage(period))
        assertEquals(
            FeatureUsage(null, emptySet<UUID>()),
            usage.usage(StatsPeriod(LocalDate.parse("2032-07-01"), LocalDate.parse("2032-07-01"))),
        )
        assertEquals("SCHEDULE", usage.feature)
        assertFalse(usage.ownAction)
    }

    private fun lesson(isu: Int, date: String) {
        jdbc.update(
            """
            INSERT INTO lessons (id, user_isu, date, pair_id, subject_id, subject_name, start_time, end_time, type, type_id,
                group_name, flow_id, flow_type_id, format, format_id)
            VALUES (?, ?, CAST(? AS date), ?, 1, 'Synthetic subject', TIME '10:00', TIME '11:30', 'Lecture', 1, 'P0000', 1, 2,
                'Offline', 1)
            """,
            UUID.randomUUID(),
            isu,
            date,
            nextPair++,
        )
    }
}
