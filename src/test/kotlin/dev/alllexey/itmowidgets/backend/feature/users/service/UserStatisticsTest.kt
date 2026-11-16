package dev.alllexey.itmowidgets.backend.feature.users.service

import dev.alllexey.itmowidgets.backend.feature.users.model.AccountGroups
import dev.alllexey.itmowidgets.backend.feature.users.model.CourseAccounts
import dev.alllexey.itmowidgets.backend.feature.users.model.FacultyAccounts
import dev.alllexey.itmowidgets.backend.feature.users.model.PrivacyCounts
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility.ALL
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility.FRIENDS
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility.NOBODY
import dev.alllexey.itmowidgets.backend.feature.users.model.UserStats
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
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

/**
 * The account figures on real PostgreSQL. Figures of the state now take other tests' accounts out as a delta; figures
 * of a period use 2032, a year no other test registers in. Moscow is UTC+3: its day starts at 21:00 UTC.
 */
@Import(UserStatistics::class, UserStatisticsRepository::class)
class UserStatisticsTest @Autowired constructor(private val statistics: UserStatistics, private val jdbc: JdbcTemplate) :
    PostgreSqlRepositoryTest() {
    @Test
    fun `accounts and their privacy settings count every account but the placeholders of deleted ones`() {
        val before = statistics.stats()
        jdbc.insertUser(984_700)
        val open = jdbc.insertUser(984_701)
        jdbc.update(
            "UPDATE user_settings SET schedule_visibility = 'ALL', sport_visibility = 'NOBODY', friends_visibility = 'FRIENDS' WHERE user_id = ?",
            open,
        )
        // A placeholder as AccountDeletionService creates it.
        val placeholder = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, isu, name) VALUES (?, -984700, 'Deleted account')", placeholder)
        jdbc.update(
            """
            INSERT INTO user_settings (user_id, auto_sign_limit, sport_visibility, schedule_visibility, friends_visibility)
            VALUES (?, 0, 'NOBODY', 'NOBODY', 'NOBODY')
            """,
            placeholder,
        )

        val after = statistics.stats()

        assertEquals(2, after.accounts - before.accounts)
        assertEquals(counts(ALL to 1, FRIENDS to 1), delta(before, after) { it.schedule })
        assertEquals(counts(FRIENDS to 1, NOBODY to 1), delta(before, after) { it.sport })
        assertEquals(counts(ALL to 1, FRIENDS to 1), delta(before, after) { it.friends })
    }

    @Test
    fun `growth counts accounts by the Moscow day they registered, and those before the period, without placeholders`() {
        val period = StatsPeriod(LocalDate.parse("2032-03-01"), LocalDate.parse("2032-03-03"))
        val before = statistics.growth(period).before
        // 23:59:59 on 2032-02-29 in Moscow: before the period.
        jdbc.insertUser(984_710, createdAt = Instant.parse("2032-02-29T20:59:59Z"))
        // 00:00 on 2032-03-01 in Moscow, still 2032-02-29 in UTC.
        jdbc.insertUser(984_711, createdAt = Instant.parse("2032-02-29T21:00:00Z"))
        jdbc.insertUser(984_712, createdAt = Instant.parse("2032-03-03T20:59:59Z"))
        // 00:00 on 2032-03-04 in Moscow: after the period.
        jdbc.insertUser(984_713, createdAt = Instant.parse("2032-03-03T21:00:00Z"))
        placeholder(-984_710, Instant.parse("2032-02-29T12:00:00Z"))
        placeholder(-984_711, Instant.parse("2032-03-02T12:00:00Z"))

        val growth = statistics.growth(period)

        assertEquals(1, growth.before - before)
        assertEquals(
            listOf(DayCount(LocalDate.parse("2032-03-01"), 1), DayCount(LocalDate.parse("2032-03-03"), 1)),
            growth.created.sortedBy {
                it.day
            },
        )
    }

    @Test
    fun `registrations and the accounts of ISUs or ids leave placeholders out`() {
        val inside = jdbc.insertUser(984_720, createdAt = Instant.parse("2032-03-01T10:00:00Z"))
        jdbc.insertUser(984_721, createdAt = Instant.parse("2032-03-02T10:00:00Z"))
        val placeholder = placeholder(-984_720, Instant.parse("2032-03-01T11:00:00Z"))

        assertEquals(
            mapOf(inside to Instant.parse("2032-03-01T10:00:00Z")),
            statistics.registrations(Instant.parse("2032-03-01T00:00:00Z"), Instant.parse("2032-03-02T10:00:00Z")),
        )
        assertEquals(setOf(inside), statistics.accountIds(listOf(984_720, -984_720, 984_729)))
        assertEquals(emptySet(), statistics.accountIds(emptyList()))
        assertEquals(setOf(inside), statistics.accountsAmong(setOf(inside, placeholder, UUID.randomUUID())))
        assertEquals(emptySet(), statistics.accountsAmong(emptySet()))
    }

    @Test
    fun `accounts go by the faculty and course of their highest-course group, without a group as nulls, and are counted among a set`() {
        jdbc.update("INSERT INTO qualifications (code, name) VALUES (984730, 'Synthetic qualification') ON CONFLICT DO NOTHING")
        jdbc.update("INSERT INTO faculties (id, name, short_name) VALUES (984730, 'Synthetic faculty one', 'SF1')")
        jdbc.update("INSERT INTO faculties (id, name, short_name) VALUES (984731, 'Synthetic faculty two', 'SF2')")
        val firstYear = group("S0000", course = 1, faculty = 984730)
        val thirdYear = group("S2000", course = 3, faculty = 984731)
        val before = statistics.groups(emptySet())
        // A student who moved on: the highest course decides, so SF2 and the third course.
        val movedOn = jdbc.insertUser(984_730)
        member(movedOn, firstYear)
        member(movedOn, thirdYear)
        val freshman = jdbc.insertUser(984_731)
        member(freshman, firstYear)
        val withoutGroup = jdbc.insertUser(984_732)
        placeholder(-984_730, Instant.parse("2032-01-01T00:00:00Z"))

        val after = statistics.groups(setOf(movedOn, withoutGroup, UUID.randomUUID()))

        assertEquals(
            listOf(
                FacultyAccounts("SF1", "Synthetic faculty one", 1, 0),
                FacultyAccounts("SF2", "Synthetic faculty two", 1, 1),
            ),
            after.faculties.filter { it.shortName in setOf("SF1", "SF2") }.sortedBy { it.shortName },
        )
        // The row without a group comes last in both lists.
        assertEquals(null to null, after.faculties.last().shortName to after.courses.last().course)
        assertEquals(1L to 1L, (after.faculty(null).accounts - before.faculty(null).accounts) to after.faculty(null).selected)
        assertEquals(1L to 1L, (after.course(null).accounts - before.course(null).accounts) to after.course(null).selected)
        assertEquals(1L to 0L, (after.course(1).accounts - before.course(1).accounts) to after.course(1).selected)
        assertEquals(1L to 1L, (after.course(3).accounts - before.course(3).accounts) to after.course(3).selected)
        assertEquals(after.faculties.sumOf { it.accounts }, after.courses.sumOf { it.accounts })
    }

    private fun AccountGroups.faculty(shortName: String?) =
        faculties.firstOrNull { it.shortName == shortName } ?: FacultyAccounts(shortName, null, 0, 0)

    private fun AccountGroups.course(course: Int?) = courses.firstOrNull { it.course == course } ?: CourseAccounts(course, 0, 0)

    private fun group(name: String, course: Int, faculty: Long): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO study_groups (id, name, course, qualification_id, faculty_id) VALUES (?, ?, ?, 984730, ?)",
            id,
            name,
            course,
            faculty,
        )
        return id
    }

    private fun member(user: UUID, group: UUID) {
        jdbc.update("INSERT INTO user_study_groups (user_id, group_id) VALUES (?, ?)", user, group)
    }

    /** A placeholder as AccountDeletionService creates it, keeping the deleted account's registration time. */
    private fun placeholder(isu: Int, createdAt: Instant) = jdbc.insertUser(isu, name = "Deleted account", createdAt = createdAt)

    private fun delta(
        before: UserStats,
        after: UserStats,
        setting: (PrivacyCounts) -> Map<SharingVisibility, Long>,
    ): Map<SharingVisibility, Long> = SharingVisibility.entries.associateWith {
        setting(after.privacy).getValue(it) -
            setting(before.privacy).getValue(it)
    }

    /** Every value, 0 unless given. */
    private fun counts(vararg given: Pair<SharingVisibility, Int>): Map<SharingVisibility, Long> {
        val map = given.toMap()
        return SharingVisibility.entries.associateWith { (map[it] ?: 0).toLong() }
    }
}
