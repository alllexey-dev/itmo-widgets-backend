package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.users.web.GroupData
import java.time.Instant

/** Stored identity for admin lists: groups come from the ID token, highest course first. */
data class AdminUserSummary(val isu: Int, val name: String, val pictureUrl: String?, val groups: List<GroupData>)

data class AdminUserItem(
    val isu: Int,
    val name: String,
    val pictureUrl: String?,
    val groups: List<GroupData>,
    val roles: List<String>,
    val createdAt: Instant,
)

/**
 * The `app*` fields are the build the device last reported in `X-App-Version` and when; all null for a device that
 * never reported one (Android 2.2 and older).
 */
data class AdminDevice(
    val name: String,
    val lastLogin: Instant,
    val appVersion: String? = null,
    val appBuild: Int? = null,
    val appPlatform: AppPlatform? = null,
    val appDistribution: String? = null,
    val appVersionSeenAt: Instant? = null,
    val platform: AppPlatform = AppPlatform.ANDROID,
)

/**
 * [user] carries current study groups when the MyITMO directory answers; [groups] is every group the
 * ID token ever listed. [lastSeen] is the latest device login or web session use.
 */
data class AdminUserDetail(
    val user: AdminUserSummary,
    val roles: List<String>,
    val groups: List<GroupData>,
    val createdAt: Instant,
    val devices: List<AdminDevice>,
    val friendsCount: Long,
    val linksCount: Long,
    val restrictions: List<AdminRestriction>,
    val lastSeen: Instant?,
)
