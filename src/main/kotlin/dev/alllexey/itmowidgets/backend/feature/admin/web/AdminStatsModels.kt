package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
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
 * `SCHEDULE`). [pairing] is null until the pairing counters exist.
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
    val pairing: List<AdminPairingFunnel>?,
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

/** The pairings of one client over the period, step by step. */
data class AdminPairingFunnel(
    val client: ClientType,
    val created: Long,
    val opened: Long,
    val approved: Long,
    val claimed: Long,
    val expired: Long,
)
