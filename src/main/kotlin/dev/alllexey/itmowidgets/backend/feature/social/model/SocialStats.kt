package dev.alllexey.itmowidgets.backend.feature.social.model

import java.time.LocalDate

/** The friendship figures of one statistics period: [usersWithFriends] now, [days] one row per day of the period. */
data class SocialStats(val usersWithFriends: Long, val days: List<FriendshipStatsDay>)

/**
 * One Europe/Moscow day: friend requests sent on it and friendships accepted on it. Rejecting, cancelling and removing
 * a friend delete the row, so both count what still stands.
 */
data class FriendshipStatsDay(val day: LocalDate, val requests: Long, val accepts: Long)
