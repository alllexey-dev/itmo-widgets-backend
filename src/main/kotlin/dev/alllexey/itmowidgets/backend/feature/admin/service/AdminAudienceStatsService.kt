package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.activity.model.ActivePeople
import dev.alllexey.itmowidgets.backend.feature.activity.service.PeopleStatistics
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminActivePeople
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAudienceDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAudienceStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClassicCohort
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminCourseCount
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminFacultyCount
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPeopleDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminRetentionCohort
import dev.alllexey.itmowidgets.backend.feature.users.service.UserStatistics
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The audience tab of the statistics page: authorizes, resolves the period and composes the account figures of
 * [UserStatistics] with the activity of [PeopleStatistics]. The faculties and courses group the accounts active in 30
 * days by asking the users feature about the set [PeopleStatistics] returns; the set itself never leaves.
 */
@Service
class AdminAudienceStatsService(
    private val access: AdminAccess,
    private val users: UserStatistics,
    private val people: PeopleStatistics,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun audience(adminId: UUID, from: LocalDate?, to: LocalDate?): AdminAudienceStats {
        access.requireAdmin(adminId)
        val period = StatsPeriod.requested(from, to, clock)
        val growth = users.growth(period)
        val activity = people.people(period)
        val groups = users.groups(activity.activeIn30Days)
        return AdminAudienceStats(
            from = period.from,
            to = period.to,
            usersBefore = growth.before,
            days = period.zeroFilled(growth.created).map { AdminAudienceDay(it.day, it.count) },
            activePeople = activity.active.let { AdminActivePeople(it.active1d, it.active7d, it.active30d) },
            peopleHistory = activity.history.map { it.active.toDay(it.day) },
            retention = activity.retention.map { AdminRetentionCohort(it.weekStart, it.size, it.retained) },
            retentionClassic = activity.classicRetention.map { AdminClassicCohort(it.weekStart, it.size, it.active) },
            faculties = groups.faculties.map { AdminFacultyCount(it.shortName, it.name, it.accounts, it.selected) },
            courses = groups.courses.map { AdminCourseCount(it.course, it.accounts, it.selected) },
        )
    }

    private fun ActivePeople.toDay(day: LocalDate) = AdminPeopleDay(day, active1d, active7d, active30d)
}
