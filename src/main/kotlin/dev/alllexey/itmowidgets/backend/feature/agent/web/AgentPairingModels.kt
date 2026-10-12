package dev.alllexey.itmowidgets.backend.feature.agent.web

import dev.alllexey.itmowidgets.backend.feature.weblogin.model.PairingKind
import java.time.Instant
import java.util.UUID

/** [keyCommitment] is base64url of SHA-256 over the target's key and pairing secret (docs/contracts/pairing.md). */
data class CreatePairingRequest(val kind: PairingKind, val keyCommitment: String, val name: String, val version: String? = null)

data class CreateSessionPairingRequest(val keyCommitment: String)

/** Returned once to the target; only the hash of [pollSecret] is stored. The shape of the web login's `CreatedChallenge`. */
data class CreatedPairing(val id: UUID, val code: String, val pollSecret: String, val expiresAt: Instant)

enum class PairingPollStatus { PENDING, OPENED, APPROVED, REJECTED, EXPIRED }

/** Only the one claiming answer ([PairingPollStatus.APPROVED]) carries [sealedBundle] and, for a new agent, [agentToken]. */
data class PairingPoll(val status: PairingPollStatus, val sealedBundle: String?, val agentToken: CreatedAgentToken?)
