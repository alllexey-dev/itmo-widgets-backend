package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientKind
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
 * One client of the user from `X-Client` (ADR 0034): an app install, a web session or an agent token. [name] is the
 * agent token's name, null for the others. [custom] marks a fork or self-built client.
 */
data class AdminClient(
    val kind: ClientKind,
    val name: String?,
    val version: String,
    val build: String?,
    val channel: String?,
    val source: String?,
    val commit: String?,
    val base: String?,
    val custom: Boolean,
    val os: String?,
    val browser: String?,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
)

/**
 * [user] and [groups] carry every group the ID token ever listed. [lastSeen] is the latest device login, web
 * session use or client request. [clients] are the user's clients, most recently seen first.
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
    /** Self-hosted clients let in by an admin (docs/contracts/agent.md#access-beta). */
    val agentAccess: Boolean,
    val clients: List<AdminClient> = emptyList(),
)

data class AdminAgentAccess(val enabled: Boolean)
