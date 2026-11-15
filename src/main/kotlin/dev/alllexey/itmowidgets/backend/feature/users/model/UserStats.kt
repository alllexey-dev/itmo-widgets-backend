package dev.alllexey.itmowidgets.backend.feature.users.model

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
