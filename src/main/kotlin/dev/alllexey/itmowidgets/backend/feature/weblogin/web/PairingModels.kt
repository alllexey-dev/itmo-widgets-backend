package dev.alllexey.itmowidgets.backend.feature.weblogin.web

import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentScope
import dev.alllexey.itmowidgets.backend.feature.weblogin.model.PairingKind
import java.time.Instant
import java.util.UUID

/** What the phone shows before it approves (docs/contracts/pairing.md); [keyCommitment] is base64url, null for web. */
data class PairingPreview(
    val challengeId: UUID,
    val kind: PairingKind,
    val name: String?,
    val version: String?,
    val userAgent: String?,
    val keyCommitment: String?,
    val scopes: List<AgentScope>?,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/** [sealedBundle] is the HPKE ciphertext in base64url for keyed kinds; [scopes] only for a new agent. Both keys may be null. */
data class ApprovePairingRequest(val sealedBundle: String?, val scopes: List<AgentScope>?)

enum class ConnectionKind { WEB_SESSION, AGENT }

/** One row of «Подключённые устройства»: a web session or an agent token of the caller. */
data class ConnectionInfo(
    val id: UUID,
    val kind: ConnectionKind,
    val name: String?,
    val userAgent: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val live: Boolean,
    val itmoSession: Boolean,
    val current: Boolean,
    val scopes: List<AgentScope>?,
)

/**
 * Pushed to the owner's devices after every new connection; sent as `{type: TYPE, payload}` through `DeviceService`.
 * [kind] is the pairing kind and may gain values.
 */
data class DeviceConnectedPayload(
    val connectionId: UUID,
    val kind: String,
    val name: String?,
    val userAgent: String?,
    val connectedAt: Instant,
) {
    companion object {
        const val TYPE = "DEVICE_CONNECTED_PAYLOAD"
    }
}
