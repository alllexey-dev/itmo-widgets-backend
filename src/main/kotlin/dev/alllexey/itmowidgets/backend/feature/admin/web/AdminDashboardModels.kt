package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkStatus
import java.time.LocalDate

/** Rolling windows end now; [links] has every [SubjectLinkStatus] key. */
data class AdminDashboardTotals(
    val users: Long,
    val newUsers7d: Long,
    val activeDevices7d: Long,
    val activeDevices30d: Long,
    val webSessions7d: Long,
    val friendships: Long,
    val links: Map<SubjectLinkStatus, Long>,
    val openCases: Long,
    val activeAutoSignEntries: Long,
    val activeFreeSignEntries: Long,
)

/** One Europe/Moscow calendar day. [activeDevices] counts devices whose latest login fell on that day. */
data class AdminDashboardDay(val date: LocalDate, val newUsers: Long, val activeDevices: Long, val createdLinks: Long)

/** [days] holds exactly 30 consecutive days, oldest first, ending today in Europe/Moscow. */
data class AdminDashboard(val totals: AdminDashboardTotals, val days: List<AdminDashboardDay>)
