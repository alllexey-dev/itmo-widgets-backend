package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.activity.model.ActivePeople
import dev.alllexey.itmowidgets.backend.feature.activity.model.ClassicCohort
import dev.alllexey.itmowidgets.backend.feature.activity.model.PeopleDay
import dev.alllexey.itmowidgets.backend.feature.activity.model.PeopleStats
import dev.alllexey.itmowidgets.backend.feature.activity.model.RetentionCohort
import dev.alllexey.itmowidgets.backend.feature.activity.service.PeopleStatistics
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminActivePeople
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAudienceDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAudienceStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClassicCohort
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminCourseCount
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminFacultyCount
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPeopleDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminRetentionCohort
import dev.alllexey.itmowidgets.backend.feature.users.model.AccountGroups
import dev.alllexey.itmowidgets.backend.feature.users.model.CourseAccounts
import dev.alllexey.itmowidgets.backend.feature.users.model.FacultyAccounts
import dev.alllexey.itmowidgets.backend.feature.users.model.UserGrowth
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRoleRepository
import dev.alllexey.itmowidgets.backend.feature.users.service.UserStatistics
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** How the audience tab composes the users and the activity statistics; the figures themselves are tested per feature. */
class AdminAudienceStatsServiceTest {
    private val admin = UUID.randomUUID()
    private val roles = mock(UserRoleRepository::class.java).also {
        `when`(it.existsByUserIdAndRole(admin, UserRole.ADMIN)).thenReturn(true)
    }
    private val users = mock(UserStatistics::class.java)
    private val people = mock(PeopleStatistics::class.java)
    private val clock = Clock.fixed(Instant.parse("2032-03-03T09:00:00Z"), ZoneOffset.UTC)
    private val service = AdminAudienceStatsService(AdminAccess(roles), users, people, clock)
    private val period = StatsPeriod(LocalDate.parse("2032-03-01"), LocalDate.parse("2032-03-03"))

    @Test
    fun `growth is zero-filled, and the accounts active in 30 days are grouped by the users feature`() {
        val active = setOf(UUID.randomUUID(), UUID.randomUUID())
        `when`(users.growth(period)).thenReturn(UserGrowth(before = 40, created = listOf(DayCount(LocalDate.parse("2032-03-02"), 3))))
        `when`(people.people(period)).thenReturn(
            PeopleStats(
                active = ActivePeople(1, 2, 5),
                history = listOf(PeopleDay(LocalDate.parse("2032-03-02"), ActivePeople(1, 3, 4))),
                retention = listOf(RetentionCohort(LocalDate.parse("2032-03-01"), 3, emptyList())),
                classicRetention = listOf(ClassicCohort(LocalDate.parse("2032-03-01"), 3, emptyList())),
                activeIn30Days = active,
            ),
        )
        `when`(users.groups(active)).thenReturn(
            AccountGroups(
                faculties = listOf(FacultyAccounts("SF", "Synthetic faculty", 30, 2), FacultyAccounts(null, null, 13, 0)),
                courses = listOf(CourseAccounts(2, 30, 2), CourseAccounts(null, 13, 0)),
            ),
        )

        assertEquals(
            AdminAudienceStats(
                from = period.from,
                to = period.to,
                usersBefore = 40,
                days = listOf(
                    AdminAudienceDay(LocalDate.parse("2032-03-01"), 0),
                    AdminAudienceDay(LocalDate.parse("2032-03-02"), 3),
                    AdminAudienceDay(LocalDate.parse("2032-03-03"), 0),
                ),
                activePeople = AdminActivePeople(1, 2, 5),
                peopleHistory = listOf(AdminPeopleDay(LocalDate.parse("2032-03-02"), 1, 3, 4)),
                retention = listOf(AdminRetentionCohort(LocalDate.parse("2032-03-01"), 3, emptyList())),
                retentionClassic = listOf(AdminClassicCohort(LocalDate.parse("2032-03-01"), 3, emptyList())),
                faculties = listOf(AdminFacultyCount("SF", "Synthetic faculty", 30, 2), AdminFacultyCount(null, null, 13, 0)),
                courses = listOf(AdminCourseCount(2, 30, 2), AdminCourseCount(null, 13, 0)),
            ),
            service.audience(admin, period.from, period.to),
        )
    }

    @Test
    fun `a non-admin is refused before any figure is read`() {
        assertFailsWith<PermissionDeniedException> { service.audience(UUID.randomUUID(), null, null) }
        verifyNoInteractions(users, people)
    }
}
