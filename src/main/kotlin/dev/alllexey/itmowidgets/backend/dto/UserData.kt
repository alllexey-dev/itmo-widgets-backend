package dev.alllexey.itmowidgets.backend.dto


/** Public identity with access computed for the authenticated viewer, never owner privacy settings. */
data class UserData(
    val isu: Int,
    val name: String,
    val pictureUrl: String?,
    val groups: List<GroupData>,
    val capabilities: UserCapabilities,
)
