package dev.alllexey.itmowidgets.backend.feature.users.web

/** Public identity with access computed for the authenticated viewer, never owner privacy settings. */
data class UserData(
    val isu: Int,
    val name: String,
    val pictureUrl: String?,
    val groups: List<GroupData>,
    val capabilities: UserCapabilities,
    /** `male` or `female` as ITMO.ID sends it, for declension; null until the owner uploads the id token. */
    val gender: String? = null,
    /** The ITMO.ID `family_name` and `given_name`, for initials; null until the owner uploads the id token. */
    val familyName: String? = null,
    val givenName: String? = null,
)
