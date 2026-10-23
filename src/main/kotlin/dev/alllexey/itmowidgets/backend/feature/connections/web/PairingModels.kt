package dev.alllexey.itmowidgets.backend.feature.connections.web

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairKind
import java.time.Instant
import java.util.UUID

/**
 * A client asks to connect (docs/contracts/connections.md#pairing). [keyCommitment] is base64url of SHA-256 over the
 * client's key and pairing secret; [scopes] within the client's allowed set, its defaults when absent or empty.
 */
data class CreateChallengeRequest(
    val kind: PairKind,
    val client: ClientType,
    val keyCommitment: String,
    val name: String,
    val version: String? = null,
    val platform: String? = null,
    val scopes: List<ConnectionScope>? = null,
)

data class RenewChallengeRequest(val keyCommitment: String)

/** Returned once to the client; only the hash of [claimSecret] is stored, and it never goes into the QR. */
data class CreatedChallenge(val challengeId: UUID, val code: String, val claimSecret: String, val expiresAt: Instant)

/** What the phone shows before it approves; [connectionId] only for a renewal. */
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
)

/**
 * Every key is sent, any may be null: [sealedBundle] (HPKE ciphertext, base64url) and [itmo] are set for an agent or
 * MCP and null for the web; [scopes] only on a new connection, a subset of the requested ones, null keeps them all.
 */
data class ApprovePairingRequest(val sealedBundle: String?, val scopes: List<ConnectionScope>?, val itmo: ItmoSession?)

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
