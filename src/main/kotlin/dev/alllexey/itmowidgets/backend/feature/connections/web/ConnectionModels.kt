package dev.alllexey.itmowidgets.backend.feature.connections.web

import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.model.ItmoStatus
import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/** A client's own ITMO.ID session as the phone reported it at the approval: its id and its end. */
data class ItmoSession(val sid: String, val expiresAt: Instant)

/**
 * One connection of the caller (docs/contracts/connections.md#devices). [itmo] is null for the web; [revokedAt] is set
 * only on a revoked connection whose ITMO.ID session the phone has not ended yet.
 */
data class ConnectionInfo(
    val id: UUID,
    val client: ClientType,
    val name: String,
    val version: String?,
    val platform: String?,
    val scopes: List<ConnectionScope>,
    val createdAt: Instant,
    val approvedAt: Instant,
    val lastUsedAt: Instant?,
    val live: Boolean,
    val itmo: ItmoSession?,
    val itmoStatus: ItmoStatus,
    val revokedAt: Instant?,
)

/** A registered app install of the caller; [current] is the install making the request. */
data class InstallInfo(
    val instanceId: UUID,
    val platform: AppPlatform,
    val deviceName: String?,
    val os: String?,
    val version: String,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val pushEnabled: Boolean,
    val sid: String?,
    val current: Boolean,
)

/** «Устройства»: the caller's connections and app installs. */
data class UserDevices(val connections: List<ConnectionInfo>, val installs: List<InstallInfo>)

/** [scopes] within the connection's allowed set; widening only from the app. */
data class UpdateScopesRequest(val scopes: List<ConnectionScope>)

/** What a connected client reports about itself: its ITMO.ID session state and its version. */
data class ClientStatusRequest(val itmo: ItmoStatus, val version: String? = null)

/** The caller's ISU and own connection; the web reads the rest from `/api/users/me/data` and `/api/users/me/roles`. */
data class ClientMe(val isu: Int, val connection: ConnectionInfo)

/** One event: [type] and [payload] are the push envelope of the same message, [id] its idempotency key. */
data class ClientEvent(
    val seq: Long,
    val id: String,
    val type: String,
    val occurredAt: Instant,
    val expiresAt: Instant,
    @get:Schema(implementation = Map::class, description = "The payload of the push of the same type, nulls omitted")
    val payload: JsonNode,
)

data class ClientEventsPage(val events: List<ClientEvent>, val nextCursor: Long, val hasMore: Boolean, val gap: Boolean)

/**
 * Pushed to the owner's devices after every new connection; sent as `{type: TYPE, payload}` through `DeviceService`.
 * [client] may gain values.
 */
data class DeviceConnectedPayload(val connectionId: UUID, val client: String, val name: String, val connectedAt: Instant) {
    companion object {
        const val TYPE = "DEVICE_CONNECTED_PAYLOAD"
    }
}
