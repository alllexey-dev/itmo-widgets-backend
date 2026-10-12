package dev.alllexey.itmowidgets.backend.feature.weblogin.service

import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentScope
import dev.alllexey.itmowidgets.backend.feature.agent.service.AgentTokenService
import dev.alllexey.itmowidgets.backend.feature.agent.service.IssuedAgentToken
import dev.alllexey.itmowidgets.backend.feature.weblogin.model.PairingKind
import dev.alllexey.itmowidgets.backend.feature.weblogin.model.WebLoginChallengeEntity
import dev.alllexey.itmowidgets.backend.feature.weblogin.model.WebLoginStatus
import dev.alllexey.itmowidgets.backend.feature.weblogin.persistence.WebLoginChallengeRepository
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.TooManyRequestsException
import dev.alllexey.itmowidgets.backend.platform.ratelimit.WindowLimiter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** A challenge the target created; only the hash of [pollSecret] is stored. */
data class NewPairing(val id: UUID, val code: String, val pollSecret: String, val expiresAt: Instant)

/** What the phone sees before it approves; [keyCommitment] lets it check the scanned key against the code. */
data class PairingView(
    val challengeId: UUID,
    val kind: PairingKind,
    val name: String?,
    val version: String?,
    val userAgent: String?,
    val keyCommitment: ByteArray?,
    val scopes: Set<AgentScope>?,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/** A target's poll answer; only [Approved] carries the bundle and, for `AGENT`, the new token. */
sealed interface PairingClaim {
    data object Pending : PairingClaim
    data object Opened : PairingClaim
    data object Rejected : PairingClaim
    data object Expired : PairingClaim

    /** [connectionId] is the agent token's id, the row of the connections list. */
    class Approved(
        val challengeId: UUID,
        val connectionId: UUID,
        val userId: UUID,
        val kind: PairingKind,
        val targetName: String?,
        val sealedBundle: ByteArray,
        val agentToken: IssuedAgentToken?,
    ) : PairingClaim
}

/**
 * Device pairing on the web login challenges (docs/contracts/pairing.md): a target creates a challenge with a commitment
 * to its key, the phone opens and approves it with an HPKE bundle, and exactly one poll of the target claims it. Backend
 * never sees a key, a pairing secret or an ITMO token; logs carry ids and kinds only.
 */
@Service
class PairingService(
    private val challenges: WebLoginChallengeRepository,
    private val logins: WebLoginService,
    private val agentTokens: AgentTokenService,
    private val clock: Clock,
) {
    private val sessionLimiter = WindowLimiter(SESSION_PAIRINGS_PER_HOUR, Duration.ofHours(1), clock)
    private val pollLimiter = WindowLimiter(1, Duration.ofSeconds(1), clock)
    private val openLimiter = WindowLimiter(OPENS_PER_WINDOW, Duration.ofMinutes(10), clock)
    private val decisionLimiter = WindowLimiter(DECISIONS_PER_HOUR, Duration.ofHours(1), clock)

    /** `POST /api/pair/challenges`: a new agent, anonymous, limited per address together with web logins. */
    @Transactional
    fun create(kind: PairingKind, keyCommitment: String, name: String, version: String?, userAgent: String?, clientIp: String): NewPairing {
        if (kind != PairingKind.AGENT) throw InvalidRequestDataException("Only AGENT pairings are created here")
        val commitment = decode(keyCommitment, COMMITMENT_BYTES, COMMITMENT_BYTES, "keyCommitment")
        val cleanName = text(name, MAX_NAME, "name") ?: throw InvalidRequestDataException("name is required")
        val cleanVersion = version?.let { text(it, MAX_VERSION, "version") }
        val now = clock.instant()
        if (challenges.countByClientIpSince(clientIp, now.minus(WebLoginService.RATE_WINDOW)) >=
            WebLoginService.MAX_UNAPPROVED_PER_WINDOW
        ) {
            throw TooManyRequestsException("Too many pairing codes, try again later")
        }
        return save(
            kind,
            commitment,
            cleanName,
            cleanVersion,
            userAgent,
            clientIp,
            agentTokenId = null,
            scopes = null,
            now,
        )
    }

    /** `POST /api/agent/pair/session`: a connected agent asks for a new ITMO session; name and scopes are its token's. */
    @Transactional
    fun createSession(tokenId: UUID, keyCommitment: String, clientIp: String): NewPairing {
        val commitment = decode(keyCommitment, COMMITMENT_BYTES, COMMITMENT_BYTES, "keyCommitment")
        val token = agentTokens.find(tokenId)?.takeIf { it.revokedAt == null } ?: throw NotFoundException("Agent token not found")
        if (!sessionLimiter.tryAcquire(tokenId)) throw TooManyRequestsException("Too many session pairings, try again later")
        return save(
            PairingKind.AGENT_SESSION,
            commitment,
            token.name,
            null,
            null,
            clientIp,
            token.id,
            token.scopes,
            clock.instant(),
        )
    }

    /** The first account that opens a code owns it; anyone else gets 404, as for an unknown code. */
    @Transactional
    fun open(userId: UUID, code: String): PairingView {
        if (!openLimiter.tryAcquire(userId)) throw TooManyRequestsException("Too many codes opened, try again later")
        val now = clock.instant()
        val normalized = code.trim().uppercase()
        val challenge = normalized.takeIf(CODE_PATTERN::matches)?.let { challenges.findPendingPairingByCode(it, now) }
            ?.takeIf { it.openedBy == null || it.openedBy == userId }
            ?: throw notFound()
        requireTarget(userId, challenge)
        if (challenge.openedBy == null && challenges.open(challenge.id, userId, now, now.plus(OPENED_TTL)) != 1) throw notFound()
        log.info("Pairing challenge {} ({}) opened by user {}", challenge.id, challenge.kind, userId)
        return view(challenges.findById(challenge.id).orElseThrow(::notFound))
    }

    /** Only the opener; a repeated approval is a no-op. [sealedBundle] is base64url, [scopes] only for `AGENT`. */
    @Transactional
    fun approve(userId: UUID, challengeId: UUID, sealedBundle: String?, scopes: Set<AgentScope>?) {
        val challenge = opened(userId, challengeId)
        if (challenge.status == WebLoginStatus.APPROVED && challenge.approvedBy == userId) return
        if (challenge.status != WebLoginStatus.PENDING) throw notFound()
        val bundle = when {
            challenge.kind.keyed -> decode(
                sealedBundle ?: throw InvalidRequestDataException("sealedBundle is required"),
                MIN_BUNDLE,
                MAX_BUNDLE,
                "sealedBundle",
            )

            sealedBundle != null -> throw InvalidRequestDataException("A web sign-in carries no sealedBundle")

            else -> null
        }
        val granted = when (challenge.kind) {
            PairingKind.AGENT -> scopes?.takeIf { it.isNotEmpty() } ?: throw InvalidRequestDataException("scopes are required")
            else -> if (scopes == null) null else throw InvalidRequestDataException("scopes are set only for a new agent")
        }
        requireTarget(userId, challenge)
        if (challenges.approvePairing(challengeId, userId, clock.instant(), bundle, granted?.let(AgentScope::format)) != 1) throw notFound()
        log.info("Pairing challenge {} ({}) approved by user {}", challengeId, challenge.kind, userId)
    }

    @Transactional
    fun reject(userId: UUID, challengeId: UUID) {
        val challenge = opened(userId, challengeId)
        if (challenge.status == WebLoginStatus.REJECTED) return
        if (challenges.reject(challengeId, userId, clock.instant()) != 1) throw notFound()
        log.info("Pairing challenge {} ({}) rejected by user {}", challengeId, challenge.kind, userId)
    }

    /** `GET /api/pair/challenges/{id}`: at most one poll per second per challenge. */
    @Transactional
    fun poll(challengeId: UUID, pollSecret: String): PairingClaim {
        if (!pollLimiter.tryAcquire(challengeId)) throw TooManyRequestsException("Poll at most once a second")
        return claim(challengeId, pollSecret)
    }

    /**
     * Exactly one caller turns an approval into [PairingClaim.Approved]: the claim and the deletion of the bundle are one
     * conditional update, and an `AGENT` token is minted in the same transaction.
     */
    @Transactional
    fun claim(challengeId: UUID, pollSecret: String): PairingClaim {
        val challenge = challenges.findById(challengeId).orElse(null)
            ?.takeIf { it.kind.keyed && WebTokens.matches(pollSecret, it.pollSecretHash) }
            ?: throw NotFoundException("Pairing request not found")
        val now = clock.instant()
        return when (challenge.status) {
            WebLoginStatus.PENDING -> when {
                !now.isBefore(challenge.expiresAt) -> PairingClaim.Expired
                challenge.openedBy != null -> PairingClaim.Opened
                else -> PairingClaim.Pending
            }

            WebLoginStatus.APPROVED -> approved(challenge, now)

            WebLoginStatus.REJECTED -> PairingClaim.Rejected

            WebLoginStatus.CLAIMED, WebLoginStatus.EXPIRED -> PairingClaim.Expired
        }
    }

    private fun approved(challenge: WebLoginChallengeEntity, now: Instant): PairingClaim {
        val tokenId = challenge.agentTokenId
        if (tokenId != null && agentTokens.find(tokenId)?.revokedAt != null) {
            challenges.expireForAgentToken(tokenId)
            return PairingClaim.Expired
        }
        val bundle = challenge.sealedBundle
        if (bundle == null || !now.isBefore(challenge.expiresAt.plus(WebLoginService.CLAIM_GRACE)) ||
            challenges.markClaimed(challenge.id) != 1
        ) {
            return PairingClaim.Expired
        }
        val userId = checkNotNull(challenge.approvedBy)
        val token = if (challenge.kind == PairingKind.AGENT) {
            agentTokens.issuePaired(userId, checkNotNull(challenge.targetName), AgentScope.parse(checkNotNull(challenge.scopes)))
        } else {
            null
        }
        log.info("Pairing challenge {} ({}) claimed for user {}", challenge.id, challenge.kind, userId)
        val connectionId = checkNotNull(token?.entity?.id ?: challenge.agentTokenId)
        return PairingClaim.Approved(challenge.id, connectionId, userId, challenge.kind, challenge.targetName, bundle, token)
    }

    private fun save(
        kind: PairingKind,
        commitment: ByteArray,
        name: String,
        version: String?,
        userAgent: String?,
        clientIp: String,
        agentTokenId: UUID?,
        scopes: String?,
        now: Instant,
    ): NewPairing {
        val pollSecret = WebTokens.random()
        val challenge = challenges.save(
            WebLoginChallengeEntity(
                code = logins.freeCode(),
                pollSecretHash = WebTokens.sha256(pollSecret),
                userAgent = userAgent?.take(300),
                clientIp = clientIp.take(64),
                createdAt = now,
                expiresAt = now.plus(WebLoginService.CODE_TTL),
                kind = kind,
                keyCommitment = commitment,
                targetName = name,
                requestedByVersion = version,
                agentTokenId = agentTokenId,
                scopes = scopes,
            ),
        )
        log.info("Pairing challenge {} ({}) created", challenge.id, kind)
        return NewPairing(challenge.id, challenge.code, pollSecret, challenge.expiresAt)
    }

    /** The caller's opened challenge; the conditional updates check status and life. */
    private fun opened(userId: UUID, challengeId: UUID): WebLoginChallengeEntity {
        if (!decisionLimiter.tryAcquire(userId)) throw TooManyRequestsException("Too many pairing decisions, try again later")
        return challenges.findById(challengeId).orElse(null)?.takeIf { it.openedBy == userId } ?: throw notFound()
    }

    /** `AGENT` needs access and a free slot (403 or 409); `AGENT_SESSION` only its token's owner; `WEB_ITMO` is not served yet. */
    private fun requireTarget(userId: UUID, challenge: WebLoginChallengeEntity) {
        when (challenge.kind) {
            PairingKind.WEB_SESSION -> Unit

            PairingKind.AGENT -> agentTokens.requireCanIssue(userId)

            PairingKind.AGENT_SESSION -> {
                val token = challenge.agentTokenId?.let(agentTokens::find)
                if (token == null || token.userId != userId || token.revokedAt != null) throw notFound()
            }

            PairingKind.WEB_ITMO -> throw notFound()
        }
    }

    private fun view(challenge: WebLoginChallengeEntity) = PairingView(
        challenge.id,
        challenge.kind,
        challenge.targetName,
        challenge.requestedByVersion,
        challenge.userAgent,
        challenge.keyCommitment,
        challenge.scopes?.takeIf { challenge.kind == PairingKind.AGENT_SESSION }?.let(AgentScope::parse),
        challenge.createdAt,
        challenge.expiresAt,
    )

    private fun notFound() = NotFoundException("Pairing code not found or expired")

    companion object {
        /** A code lives this long after its first open. */
        val OPENED_TTL: Duration = Duration.ofMinutes(10)
        const val COMMITMENT_BYTES = 32

        /** HPKE `enc` (32 bytes) and the AEAD tag (16 bytes) at least. */
        const val MIN_BUNDLE = 48
        const val MAX_BUNDLE = 16_384
        const val MAX_NAME = 60
        const val MAX_VERSION = 40
        const val SESSION_PAIRINGS_PER_HOUR = 10
        const val OPENS_PER_WINDOW = 30
        const val DECISIONS_PER_HOUR = 20
        private val CODE_PATTERN = Regex("^[${WebLoginService.CODE_ALPHABET}]{${WebLoginService.CODE_LENGTH}}$")
        private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")
        private val log = LoggerFactory.getLogger(PairingService::class.java)

        fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** Base64url without padding, [min] to [max] bytes; the length is checked before decoding. */
        fun decode(value: String, min: Int, max: Int, field: String): ByteArray {
            if (value.length > (max * 4 + 2) / 3 || !BASE64URL.matches(value)) throw InvalidRequestDataException("$field is not base64url")
            val bytes = runCatching {
                Base64.getUrlDecoder().decode(value)
            }.getOrElse { throw InvalidRequestDataException("$field is not base64url") }
            if (bytes.size !in min..max) throw InvalidRequestDataException("$field must be $min to $max bytes")
            return bytes
        }

        /** Trimmed, at most [max] characters, no control characters; null when blank. */
        private fun text(value: String, max: Int, field: String): String? {
            val trimmed = value.trim()
            if (trimmed.length > max || trimmed.any(Char::isISOControl)) {
                throw InvalidRequestDataException("$field must be at most $max characters without control characters")
            }
            return trimmed.ifEmpty { null }
        }
    }
}
