package dev.alllexey.itmowidgets.backend.feature.users.web

import java.time.OffsetDateTime

/** Relative to the authenticated viewer; BLOCKED is reserved, not a simulated rejection. */
enum class RelationshipState { NONE, OUTGOING, INCOMING, FRIENDS, BLOCKED }

/** [requestedAt]: when the viewer's pending request with this user was sent; set only for OUTGOING and INCOMING. */
data class UserProfile(val user: UserData, val relationship: RelationshipState, val requestedAt: OffsetDateTime? = null)

/** Registered users only, in first-occurrence request order. Unknown ISUs are omitted. */
data class UserLookupResponse(val users: List<UserProfile>)
