package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoFailureKind
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationActor
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ReportReason
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportEntryOutcome
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionOccurrenceState
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionStopReason
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import java.time.LocalDate

// The statistics page: `/api/admin/stats/**`. Every answer repeats its period; daily series have one row per
// Europe/Moscow day of it, oldest first. Counts only: no user is named.

/** `GET /api/admin/stats/sport`. [sections] holds at most 50, by entries descending; [slots] only slots with an entry. */
data class AdminSportStats(
    val from: LocalDate,
    val to: LocalDate,
    val days: List<AdminSportStatsDay>,
    val autoSign: AdminSportEntryStats,
    val freeSign: AdminSportEntryStats,
    val subscriptions: AdminSportSubscriptionStats,
    val sections: List<AdminSportSectionStats>,
    val slots: List<AdminSportSlotStats>,
)

/** Entries created on [date] by kind, and [signUps], entries of both kinds satisfied on it whenever created. */
data class AdminSportStatsDay(
    val date: LocalDate,
    val autoSign: AdminSportDayEntries,
    val freeSign: AdminSportDayEntries,
    val signUps: Long,
)

data class AdminSportDayEntries(val created: Long, val satisfied: Long)

/** [outcomes] has every [SportEntryOutcome]; the times are over [timed] satisfied entries, null when it is 0. */
data class AdminSportEntryStats(
    val created: Long,
    val outcomes: Map<SportEntryOutcome, Long>,
    val timed: Long,
    val medianSeconds: Long?,
    val p90Seconds: Long?,
)

/** [active] and [stopped] are every subscription now; [created] and [occurrences] belong to the period. */
data class AdminSportSubscriptionStats(
    val active: Long,
    val stopped: Map<SportSectionStopReason, Long>,
    val created: Long,
    val occurrences: Map<SportSectionOccurrenceState, Long>,
)

data class AdminSportSectionStats(
    val sectionName: String,
    val autoSignEntries: Long,
    val freeSignEntries: Long,
    val satisfied: Long,
    val medianWaitSeconds: Long?,
)

/** [weekday] 1 (Monday) to 7 (Sunday) and [timeStart] `HH:mm` of the targeted lesson in Europe/Moscow. */
data class AdminSportSlotStats(val weekday: Int, val timeStart: String, val entries: Long, val satisfied: Long)

/** `GET /api/admin/stats/moderation`. [ownReviews] is every own teacher review now; the lists belong to the period. */
data class AdminModerationStats(
    val from: LocalDate,
    val to: LocalDate,
    val days: List<AdminModerationStatsDay>,
    val ownReviews: Long,
    val cases: List<AdminCaseStats>,
    val decisions: List<AdminDecisionStats>,
    val reports: List<AdminReportStats>,
)

/**
 * Reviews and votes created on [date] (the votes that still stand), cases opened, resolved and withdrawn on it,
 * [backlog] the cases open at its end, and [reports] created on it.
 */
data class AdminModerationStatsDay(
    val date: LocalDate,
    val newReviews: Long,
    val reviewVotes: Long,
    val externalReviewVotes: Long,
    val linkVotes: Long,
    val casesOpened: Long,
    val casesResolved: Long,
    val casesWithdrawn: Long,
    val backlog: Long,
    val reports: Long,
)

/** Cases opened in the period; the decision times are over the [resolved] ones, null without one. */
data class AdminCaseStats(
    val targetType: ModerationTargetType,
    val reason: ModerationCaseReason,
    val opened: Long,
    val resolved: Long,
    val withdrawn: Long,
    val medianSecondsToDecision: Long?,
    val maxSecondsToDecision: Long?,
)

/** Decisions made in the period; [actor] is a moderator, or the policy and the ISU check. */
data class AdminDecisionStats(
    val targetType: ModerationTargetType,
    val action: ModerationAction,
    val actor: ModerationActor,
    val count: Long,
)

/** Reports created in the period and how many of them are dismissed now. */
data class AdminReportStats(val targetType: ModerationTargetType, val reason: ReportReason, val total: Long, val dismissed: Long)

/**
 * `GET /api/admin/stats/features`. [users] is every account now and [usersWithFriends] the accounts with an accepted
 * friendship now; [socialUsers] the distinct people behind the [features] rows of their own actions (every row but
 * `SCHEDULE`). [pairing] has one row per client.
 */
data class AdminFeatureStats(
    val from: LocalDate,
    val to: LocalDate,
    val users: Long,
    val usersWithFriends: Long,
    val socialUsers: Long,
    val features: List<AdminFeatureUsage>,
    val days: List<AdminFeatureStatsDay>,
    val privacy: AdminPrivacyStats,
    val pairing: List<AdminPairingFunnel>,
)

/** One feature over the period: [events] it counts (null for `SCHEDULE`) and the distinct [users] behind them. */
data class AdminFeatureUsage(val feature: String, val events: Long?, val users: Long)

/** Friend requests sent and accepted on [date], and connections created and revoked on it per client. */
data class AdminFeatureStatsDay(
    val date: LocalDate,
    val friendRequests: Long,
    val friendAccepts: Long,
    val connectionsCreated: Map<ClientType, Long>,
    val connectionsRevoked: Map<ClientType, Long>,
)

/** Accounts per value of each privacy setting now; every map has every [SharingVisibility]. */
data class AdminPrivacyStats(
    val schedule: Map<SharingVisibility, Long>,
    val sport: Map<SharingVisibility, Long>,
    val friends: Map<SharingVisibility, Long>,
)

/** The `CONNECT` pairings of one client over the period, step by step, from the pairing counters. */
data class AdminPairingFunnel(
    val client: ClientType,
    val created: Long,
    val opened: Long,
    val approved: Long,
    val claimed: Long,
    val expired: Long,
)

/**
 * `GET /api/admin/stats/audience`. [usersBefore] and [days] are the growth of the accounts; [activePeople] and
 * [faculties] and [courses] are the state now; [peopleHistory] the nightly rows of the last 90 days; [retention] and
 * [retentionClassic] one row per registration week starting in the period, the latest 26.
 */
data class AdminAudienceStats(
    val from: LocalDate,
    val to: LocalDate,
    val usersBefore: Long,
    val days: List<AdminAudienceDay>,
    val activePeople: AdminActivePeople,
    val peopleHistory: List<AdminPeopleDay>,
    val retention: List<AdminRetentionCohort>,
    val retentionClassic: List<AdminClassicCohort>,
    val faculties: List<AdminFacultyCount>,
    val courses: List<AdminCourseCount>,
)

/** Accounts created on [date]. */
data class AdminAudienceDay(val date: LocalDate, val newUsers: Long)

/** Distinct people whose last activity is in the rolling 1, 7 and 30 days. */
data class AdminActivePeople(val active1d: Long, val active7d: Long, val active30d: Long)

/** The nightly count of [day]: the people active in the 1, 7 and 30 days up to the end of it. */
data class AdminPeopleDay(val day: LocalDate, val active1d: Long, val active7d: Long, val active30d: Long)

/** `retained[n-1]`: accounts of the week with a last activity at least `n` weeks after their registration. */
data class AdminRetentionCohort(val weekStart: LocalDate, val size: Long, val retained: List<Long>)

/** `active[n]`: accounts of the week that made a request in its `n`-th calendar week; null before the collection. */
data class AdminClassicCohort(val weekStart: LocalDate, val size: Long, val active: List<Long?>)

/** Accounts of a faculty by their current group, and those active in 30 days; nulls for accounts without a group. */
data class AdminFacultyCount(val facultyShortName: String?, val facultyName: String?, val users: Long, val active30d: Long)

/** Accounts of a course by their current group, and those active in 30 days; null for accounts without a group. */
data class AdminCourseCount(val course: Int?, val users: Long, val active30d: Long)

/**
 * `GET /api/admin/stats/server`. [collectedSince] is the first day with any counter, null before one; the days before
 * it are zeros and nulls. [routes], [myItmo] and [push] cover the whole period, most used first.
 */
data class AdminServerStats(
    val from: LocalDate,
    val to: LocalDate,
    val collectedSince: LocalDate?,
    val days: List<AdminServerDay>,
    val routes: List<AdminRouteStats>,
    val myItmo: List<AdminUpstreamStats>,
    val push: List<AdminPushStats>,
)

/** The answers, My ITMO calls and pushes of [date]; the times are null on a day without a timed answer. */
data class AdminServerDay(
    val date: LocalDate,
    val requests: Long,
    val errors5xx: Long,
    val p50Millis: Int?,
    val p95Millis: Int?,
    val myItmoCalls: Long,
    val myItmoFailures: Long,
    val pushSent: Long,
    val pushFailed: Long,
    val pushTokenGone: Long,
)

/** One route group over the period; the times are null when none of its answers was timed. */
data class AdminRouteStats(
    val group: String,
    val requests: Long,
    val errors4xx: Long,
    val errors5xx: Long,
    val p50Millis: Int?,
    val p95Millis: Int?,
)

/** One My ITMO gateway operation over the period; [failures] has every [MyItmoFailureKind]. */
data class AdminUpstreamStats(val operation: String, val calls: Long, val failures: Map<MyItmoFailureKind, Long>)

/** One FCM `type` over the period: per device sent, failed and token gone, and the users without a device. */
data class AdminPushStats(val type: String, val sent: Long, val failed: Long, val tokenGone: Long, val noDevice: Long)
