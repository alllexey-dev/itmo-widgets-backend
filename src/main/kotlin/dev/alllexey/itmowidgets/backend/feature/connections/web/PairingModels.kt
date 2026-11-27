package dev.alllexey.itmowidgets.backend.feature.connections.web

import com.fasterxml.jackson.annotation.JsonInclude
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientDeployment
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairKind
import java.time.Instant
import java.util.UUID

/**
 * One grant a client asks for (docs/contracts/connections.md#protocol-2): a grant id of its registry entry; [required]
 * null keeps the registry's, true makes an optional one required.
 */
data class GrantRequest(val id: String, val required: Boolean? = null)

/**
 * A client asks to connect (docs/contracts/connections.md#pairing). [keyCommitment] is base64url of SHA-256 over the
 * client's key and pairing secret. Exactly one of [client] (a kind, protocol 1: its seeded registry entry) and
 * [clientId] (a registry entry). [scopes] within the client's allowed set, its defaults when absent or empty; [grants]
 * instead of [scopes] makes the challenge protocol 2, as does a [clientId] of kind `app`.
 */
data class CreateChallengeRequest(
    val kind: PairKind,
    val client: ClientType? = null,
    val keyCommitment: String,
    val name: String,
    val version: String? = null,
    val platform: String? = null,
    val scopes: List<ConnectionScope>? = null,
    val clientId: String? = null,
    val grants: List<GrantRequest>? = null,
)

/** [grants] (only `itmo:*` ids) are the ITMO grants the client wants after the renewal and make it protocol 2. */
data class RenewChallengeRequest(val keyCommitment: String, val grants: List<GrantRequest>? = null)

/** Returned once to the client; only the hash of [claimSecret] is stored, and it never goes into the QR. */
data class CreatedChallenge(val challengeId: UUID, val code: String, val claimSecret: String, val expiresAt: Instant)

/**
 * A registered client app as the phone shows it. [verified] is whether the client proved itself with its key; always
 * false while only public clients pair.
 */
data class ClientAppInfo(
    val id: String,
    val name: String,
    val homepage: String?,
    val iconUrl: String?,
    val deployment: ClientDeployment,
    val verified: Boolean,
)

/**
 * One requested grant, in registry order. [title] and [description] are the scope texts of a `widgets:*` grant and null
 * for `itmo:*` (the app owns those texts); [purpose] is what this client does with it, from the registry. [granted] only
 * on a renewal and only for an ITMO grant: whether the connection already holds it.
 */
data class PairingGrant(
    val id: String,
    val required: Boolean,
    val title: String?,
    val description: String?,
    val purpose: String?,
    val granted: Boolean?,
)

/**
 * What the phone shows before it approves; [connectionId] only for a renewal. [protocol], [app] and [grants] are written
 * only to an app that opens with `?protocol=2`, so a released app reads the protocol-1 preview byte for byte.
 */
data class PairingPreview(
    val challengeId: UUID,
    val kind: PairKind,
    val client: ClientType,
    val name: String,
    val version: String?,
    val platform: String?,
    val requestedScopes: List<ScopeInfo>,
    val keyCommitment: String,
    val connectionId: UUID?,
    val expiresAt: Instant,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val protocol: Int? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val app: ClientAppInfo? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val grants: List<PairingGrant>? = null,
)

/**
 * Every key is sent, any may be null. Protocol 1: [sealedBundle] (HPKE ciphertext, base64url) and [itmo] are set for an
 * agent or MCP and null for the web; [scopes] only on a new connection, a subset of the requested ones, null keeps them
 * all; [granted] null. Protocol 2: [granted] the grant ids the user chose, [scopes] null, [sealedBundle] and [itmo] iff
 * [granted] has an `itmo:*` id.
 */
data class ApprovePairingRequest(
    val sealedBundle: String?,
    val scopes: List<ConnectionScope>?,
    val itmo: ItmoSession?,
    val granted: List<String>? = null,
)

data class ApprovedPairing(val connectionId: UUID)

data class ClaimPairingRequest(val claimSecret: String)

enum class PairingClaimStatus { PENDING, OPENED, CLAIMED }

/**
 * The claim's answer: `PENDING` or `OPENED` (202) until the phone approves, then once `CLAIMED` (200) with
 * [connection], [token] for a new agent or MCP (the web gets a cookie instead) and [sealedBundle] for agent and MCP.
 */
data class PairingClaimResponse(
    val status: PairingClaimStatus,
    val token: String? = null,
    val connection: ConnectionInfo? = null,
    val sealedBundle: String? = null,
)
