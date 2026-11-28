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
 * instead of [scopes] makes the challenge protocol 2, as does a [clientId] of kind `app`. [clientAssertion] is required
 * from a confidential client and forbidden for a public one.
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
    val clientAssertion: String? = null,
)

/**
 * [grants] (only `itmo:*` ids and `identity`) are what the client wants added by the renewal and make it protocol 2;
 * [clientAssertion] as on create.
 */
data class RenewChallengeRequest(val keyCommitment: String, val grants: List<GrantRequest>? = null, val clientAssertion: String? = null)

/** Returned once to the client; only the hash of [claimSecret] is stored, and it never goes into the QR. */
data class CreatedChallenge(val challengeId: UUID, val code: String, val claimSecret: String, val expiresAt: Instant)

/**
 * A registered client app as the phone shows it. [verified] is whether the client proves itself with its key (a
 * confidential entry); [returnUrl], from the registry alone, is where the phone sends the user after approving a
 * pairing the client opened on the same phone.
 */
data class ClientAppInfo(
    val id: String,
    val name: String,
    val homepage: String?,
    val iconUrl: String?,
    val deployment: ClientDeployment,
    val verified: Boolean,
    val returnUrl: String?,
)

/**
 * One requested grant, in registry order. [title] and [description] are Backend's texts of a `widgets:*` grant and of
 * `identity`, null for `itmo:*` (the app owns those texts); [purpose] is what this client does with it, from the
 * registry. [granted] only on a renewal and only for an ITMO grant or `identity`: whether the connection already holds
 * it.
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

/** [clientAssertion] is required from a confidential client and forbidden for a public one. */
data class ClaimPairingRequest(val claimSecret: String, val clientAssertion: String? = null)

enum class PairingClaimStatus { PENDING, OPENED, CLAIMED }

/**
 * The claim's answer: `PENDING` or `OPENED` (202) until the phone approves, then once `CLAIMED` (200) with
 * [connection], [token] for a new agent or MCP (the web gets a cookie instead), [sealedBundle] when the phone sealed
 * ITMO grants and [identity], Backend's signed JWT, when this approval granted `identity`.
 */
data class PairingClaimResponse(
    val status: PairingClaimStatus,
    val token: String? = null,
    val connection: ConnectionInfo? = null,
    val sealedBundle: String? = null,
    val identity: String? = null,
)

/** One public key Backend signs identity tokens with, as an RFC 8037 JWK. */
data class Jwk(
    val kty: String = "OKP",
    val crv: String = "Ed25519",
    val x: String,
    val kid: String,
    val use: String = "sig",
    val alg: String = "EdDSA",
)

/** `GET /api/pair/jwks`, written as it is, not in `ApiResponse`; no keys while identity is unavailable. */
data class Jwks(val keys: List<Jwk>)
