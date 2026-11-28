package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientAuth
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientDeployment
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientStatus
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType

/**
 * One grant of a registered client (docs/contracts/admin.md#connected-client-apps): a grant id, whether the client
 * needs it, whether it is requested when the client names none, and the owner's Russian text of what the client does
 * with it.
 */
data class ConnectionClientGrantAdmin(val id: String, val required: Boolean, val requestedByDefault: Boolean, val purpose: String?)

/** A public key of a confidential client: [publicKey] is base64url without padding of the raw 32-byte Ed25519 key. */
data class ConnectionClientKeyAdmin(val kid: String, val publicKey: String)

/**
 * A registered client app as the owner edits it; the same shape is read and written whole (`PUT` replaces the entry,
 * its grants in their order and its keys). [maxPerUser] null is the default cap; [returnUrl] where the phone sends the
 * user after a same-device approval; [keys] only for a confidential client.
 */
data class ConnectionClientAdmin(
    val id: String,
    val kind: ClientType,
    val name: String,
    val homepage: String?,
    val iconUrl: String?,
    val deployment: ClientDeployment,
    val auth: ClientAuth,
    val status: ClientStatus,
    val maxPerUser: Int?,
    val grants: List<ConnectionClientGrantAdmin>,
    val returnUrl: String?,
    val keys: List<ConnectionClientKeyAdmin>,
)
