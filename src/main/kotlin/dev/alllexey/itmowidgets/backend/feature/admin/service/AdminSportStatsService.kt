package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportDayEntries
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportEntryStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportSectionStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportSlotStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportStatsDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSportSubscriptionStats
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportDayEntries
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryStats
import dev.alllexey.itmowidgets.backend.feature.sport.service.SportStatistics
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The sport tab of the statistics page: authorizes, resolves the period and maps [SportStatistics] to the wire. */
@Service
class AdminSportStatsService(private val access: AdminAccess, private val sport: SportStatistics, private val clock: Clock) {
    @Transactional(readOnly = true)
    fun sport(adminId: UUID, from: LocalDate?, to: LocalDate?): AdminSportStats {
        access.requireAdmin(adminId)
        val period = StatsPeriod.requested(from, to, clock)
        val stats = sport.stats(period)
        return AdminSportStats(
            from = period.from,
            to = period.to,
            days = stats.days.map { AdminSportStatsDay(it.day, it.autoSign.toWire(), it.freeSign.toWire(), it.signUps) },
            autoSign = stats.autoSign.toWire(),
            freeSign = stats.freeSign.toWire(),
            subscriptions = stats.subscriptions.let {
                AdminSportSubscriptionStats(it.active, it.stopped, it.created, it.occurrences)
            },
            sections = stats.sections.map {
                AdminSportSectionStats(it.sectionName, it.autoSignEntries, it.freeSignEntries, it.satisfied, it.medianWaitSeconds)
            },
            slots = stats.slots.map { AdminSportSlotStats(it.weekday.value, it.timeStart.format(TIME), it.entries, it.satisfied) },
        )
    }

    private fun SportDayEntries.toWire() = AdminSportDayEntries(created, satisfied)

    private fun SportEntryStats.toWire() = AdminSportEntryStats(created, outcomes, waits.timed, waits.medianSeconds, waits.p90Seconds)

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
