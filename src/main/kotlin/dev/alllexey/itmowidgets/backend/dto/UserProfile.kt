package dev.alllexey.itmowidgets.backend.dto

/** Relative to the authenticated viewer; BLOCKED is reserved, not a simulated rejection. */
enum class RelationshipState { NONE, OUTGOING, INCOMING, FRIENDS, BLOCKED }

data class UserProfile(val user: UserData, val relationship: RelationshipState)

/** Registered users only, in first-occurrence request order. Unknown ISUs are omitted. */
data class UserLookupResponse(val users: List<UserProfile>)
