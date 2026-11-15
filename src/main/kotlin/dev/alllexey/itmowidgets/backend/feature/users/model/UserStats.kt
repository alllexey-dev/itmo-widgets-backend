package dev.alllexey.itmowidgets.backend.feature.users.model

import dev.alllexey.itmowidgets.backend.platform.stats.DayCount

/**
 * The account figures now: [accounts] and their [privacy] settings. A placeholder that keeps the published content of
 * a deleted account (a negative ISU, AccountDeletionService) is not an account.
 */
data class UserStats(val accounts: Long, val privacy: PrivacyCounts)

/** Accounts per value of each privacy setting; every map has every [SharingVisibility]. */
data class PrivacyCounts(
    val schedule: Map<SharingVisibility, Long>,
    val sport: Map<SharingVisibility, Long>,
    val friends: Map<SharingVisibility, Long>,
)

/** The registrations of a statistics period: the accounts created [before] it and per day of it ([created], only days with one). */
data class UserGrowth(val before: Long, val created: List<DayCount>)

/**
 * Every account by the faculty and by the course of its current group (UserPrivacyService's order: the highest course,
 * then the group name), and how many of each are in the set the caller asked about ([FacultyAccounts.selected]). An
 * account without a stored group is the row with nulls. [faculties] go by accounts descending, then by short name;
 * [courses] by course; the null row of each is last.
 */
data class AccountGroups(val faculties: List<FacultyAccounts>, val courses: List<CourseAccounts>)

data class FacultyAccounts(val shortName: String?, val name: String?, val accounts: Long, val selected: Long)

data class CourseAccounts(val course: Int?, val accounts: Long, val selected: Long)
