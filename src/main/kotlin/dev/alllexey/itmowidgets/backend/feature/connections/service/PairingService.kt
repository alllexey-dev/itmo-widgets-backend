package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionEntity
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairChallengeEntity
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairKind
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairStatus
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionRepository
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.PairChallengeRepository
import dev.alllexey.itmowidgets.backend.feature.connections.web.ApprovePairingRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.ConnectionInfo
import dev.alllexey.itmowidgets.backend.feature.connections.web.CreateChallengeRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.CreatedChallenge
import dev.alllexey.itmowidgets.backend.feature.connections.web.ItmoSession
import dev.alllexey.itmowidgets.backend.feature.connections.web.PairingPreview
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.platform.error.TooManyRequestsException
import dev.alllexey.itmowidgets.backend.platform.ratelimit.WindowLimiter
import dev.alllexey.itmowidgets.backend.platform.security.ConnectionAuthFilter
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** A claim's answer: still waiting for the phone, or the one claiming answer. */
sealed interface PairingClaim {
    data class Waiting(val opened: Boolean) : PairingClaim

    /** [token] is the new connection token of a `CONNECT`, [sealedBundle] the client's ciphertext (agent, MCP). */
    class Claimed(val userId: UUID, val kind: PairKind, val token: String?, val connection: ConnectionInfo, val sealedBundle: ByteArray?) :
        PairingClaim
}

/**
 * Pairing (docs/contracts/connections.md#pairing): a client creates a challenge with a commitment to its key, the phone
 * opens and approves it (with the HPKE bundle and the client's ITMO.ID session for an agent or MCP), and the client
 * claims it exactly once with its claim secret. Backend never sees a key, a pairing secret or an ITMO token; logs carry
 * ids, kinds and client types only.
 */
@Service
class PairingService(
    private val challenges: PairChallengeRepository,
    private val connections: ConnectionRepository,
    private val connectionService: ConnectionService,
    private val access: ClientAccessService,
    private val users: UserRepository,
    private val config: ConnectionsConfig,
    private val clock: Clock,
) {
    private val random = SecureRandom()
    private val renewLimiter = WindowLimiter(RENEWALS_PER_HOUR, Duration.ofHours(1), clock)
    private val claimLimiter = WindowLimiter(1, Duration.ofSeconds(1), clock)
    private val openLimiter = WindowLimiter(OPENS_PER_WINDOW, Duration.ofMinutes(10), clock)
    private val decisionLimiter = WindowLimiter(DECISIONS_PER_HOUR, Duration.ofHours(1), clock)

    /** `POST /api/pair/challenges`: anonymous, limited per address. */
    @Transactional
    fun create(request: CreateChallengeRequest, clientIp: String): CreatedChallenge {
        if (request.kind != PairKind.CONNECT) throw InvalidRequestDataException("Only CONNECT challenges are created here")
        val commitment = decode(request.keyCommitment, COMMITMENT_BYTES, COMMITMENT_BYTES, "keyCommitment")
        val name = text(request.name, MAX_NAME, "name") ?: throw InvalidRequestDataException("name is required")
        val version = request.version?.let { text(it, MAX_VERSION, "version") }
        val platform = request.platform?.let { text(it, MAX_PLATFORM, "platform") }
        val scopes = request.scopes?.toSet()?.takeIf { it.isNotEmpty() } ?: request.client.defaults
        if (!request.client.allowed.containsAll(scopes)) {
            throw InvalidRequestDataException("scopes must be within ${request.client.wire}'s allowed scopes")
        }
        val now = clock.instant()
        if (challenges.countByClientIpSince(clientIp, now.minus(RATE_WINDOW)) >= MAX_UNCLAIMED_PER_WINDOW) {
            throw TooManyRequestsException("Too many pairing codes, try again later")
        }
        return save(PairKind.CONNECT, request.client, commitment, name, version, platform, scopes, clientIp, null, now)
    }

    /** `POST /api/client/pair/renew`: a new approval (and for an agent or MCP a new ITMO.ID session) for a live connection. */
    @Transactional
    fun renew(connectionId: UUID, keyCommitment: String, clientIp: String): CreatedChallenge {
        val commitment = decode(keyCommitment, COMMITMENT_BYTES, COMMITMENT_BYTES, "keyCommitment")
        val connection = connections.findById(connectionId).orElse(null)?.takeIf { it.revokedAt == null }
            ?: throw NotFoundException("Connection not found")
        if (!renewLimiter.tryAcquire(connectionId)) throw TooManyRequestsException("Too many renewals, try again later")
        return save(
            PairKind.RENEW,
            connection.client,
            commitment,
            connection.name,
            connection.version,
            connection.platform,
            connection.scopeSet,
            clientIp,
            connection.id,
            clock.instant(),
        )
    }

    /** The first account that opens a code owns it; anyone else gets 404, as for an unknown code. */
    @Transactional
    fun open(userId: UUID, code: String): PairingPreview {
        if (!openLimiter.tryAcquire(userId)) throw TooManyRequestsException("Too many codes opened, try again later")
        val now = clock.instant()
        val challenge = code.trim().uppercase().takeIf(CODE_PATTERN::matches)?.let { challenges.findPendingByCode(it, now) }
            ?.takeIf { it.openedBy == null || it.openedBy == userId }
            ?: throw notFound()
        requireTarget(userId, challenge)
        if (challenge.openedBy == null && challenges.open(challenge.id, userId, now, now.plus(OPENED_TTL)) != 1) throw notFound()
        log.info("Pairing challenge {} ({} {}) opened by user {}", challenge.id, challenge.kind, challenge.client, userId)
        val opened = challenges.findById(challenge.id).orElseThrow(::notFound)
        return PairingPreview(
            opened.id,
            opened.kind,
            opened.client,
            opened.name,
            opened.version,
            opened.platform,
            opened.scopeSet.sorted(),
            encode(opened.keyCommitment),
            opened.connectionId,
            opened.expiresAt,
        )
    }

    /**
     * Only the opener; a repeated approval is a no-op. A `CONNECT` creates the connection (its token comes at the
     * claim), a `RENEW` replaces the connection's ITMO.ID session and moves its approval time.
     */
    @Transactional
    fun approve(userId: UUID, challengeId: UUID, request: ApprovePairingRequest): UUID {
        if (!decisionLimiter.tryAcquire(userId)) throw TooManyRequestsException("Too many pairing decisions, try again later")
        val challenge = challenges.findById(challengeId).orElse(null)?.takeIf { it.openedBy == userId } ?: throw notFound()
        if (challenge.status == PairStatus.APPROVED) return checkNotNull(challenge.connectionId)
        if (challenge.status != PairStatus.PENDING) throw notFound()
        val itmoClient = challenge.client.hasItmoSession
        val bundle = request.sealedBundle?.let { decode(it, MIN_BUNDLE, MAX_BUNDLE, "sealedBundle") }
        if ((bundle != null) != itmoClient) {
            throw InvalidRequestDataException(if (itmoClient) "sealedBundle is required" else "The web gets no sealedBundle")
        }
        val itmo = request.itmo
        if ((itmo != null) != itmoClient) throw InvalidRequestDataException(if (itmoClient) "itmo is required" else "The web gets no itmo")
        val now = clock.instant()
        itmo?.let { requireSession(it, now) }
        val scopes = request.scopes?.toSet()
        if (scopes != null && (challenge.kind == PairKind.RENEW || scopes.isEmpty() || !challenge.scopeSet.containsAll(scopes))) {
            throw InvalidRequestDataException("scopes are a non-empty subset of the requested ones, and only for a new connection")
        }
        users.lockById(userId) ?: throw NotFoundException("User not found")
        requireTarget(userId, challenge)
        val connectionId = when (challenge.kind) {
            PairKind.CONNECT -> connections.save(
                ConnectionEntity(
                    userId = userId,
                    client = challenge.client,
                    name = challenge.name,
                    version = challenge.version,
                    platform = challenge.platform,
                    scopes = ConnectionScope.format(scopes ?: challenge.scopeSet),
                    createdAt = now,
                    approvedAt = now,
                    itmoSid = itmo?.sid,
                    itmoExpiresAt = itmo?.expiresAt,
                ),
            ).id

            PairKind.RENEW -> {
                val connection = connections.findById(checkNotNull(challenge.connectionId)).orElseThrow(::notFound)
                connection.approvedAt = now
                if (itmo != null) {
                    connection.itmoSid = itmo.sid
                    connection.itmoExpiresAt = itmo.expiresAt
                    connection.itmoEndedAt = null
                }
                connection.id
            }
        }
        if (challenges.approve(challengeId, userId, now, bundle, connectionId) != 1) throw notFound()
        log.info("Pairing challenge {} ({} {}) approved by user {}", challengeId, challenge.kind, challenge.client, userId)
        return connectionId
    }

    /**
     * `POST /api/pair/challenges/{id}/claim`, at most once a second per challenge. Exactly one caller turns an approval
     * into [PairingClaim.Claimed]: the claim and the deletion of the bundle are one conditional update, and a
     * `CONNECT` token is minted in the same transaction. Unknown, expired and already claimed challenges are 404.
     */
    @Transactional
    fun claim(challengeId: UUID, claimSecret: String): PairingClaim {
        if (!claimLimiter.tryAcquire(challengeId)) throw TooManyRequestsException("Claim at most once a second")
        val challenge = challenges.findById(challengeId).orElse(null)
            ?.takeIf { ConnectionTokens.isWellFormed(claimSecret) && ConnectionTokens.matches(claimSecret, it.claimSecretHash) }
            ?: throw notFound()
        val now = clock.instant()
        return when (challenge.status) {
            PairStatus.PENDING -> if (now.isBefore(
                    challenge.expiresAt,
                )
            ) {
                PairingClaim.Waiting(challenge.openedBy != null)
            } else {
                throw notFound()
            }

            PairStatus.APPROVED -> claimed(challenge, now)

            PairStatus.CLAIMED, PairStatus.EXPIRED -> throw notFound()
        }
    }

    private fun claimed(challenge: PairChallengeEntity, now: Instant): PairingClaim.Claimed {
        val connectionId = checkNotNull(challenge.connectionId)
        val live = connections.findById(connectionId).map { it.revokedAt == null }.orElse(false)
        if (!live || !now.isBefore(challenge.expiresAt.plus(CLAIM_GRACE)) || challenges.markClaimed(challenge.id) != 1) throw notFound()
        // The conditional update cleared the persistence context: load the connection again to change it.
        val connection = connections.findById(connectionId).orElseThrow(::notFound)
        val token = if (challenge.kind == PairKind.CONNECT) {
            (ConnectionAuthFilter.PREFIX + ConnectionTokens.random()).also { connection.tokenHash = ConnectionTokens.sha256(it) }
        } else {
            null
        }
        log.info("Pairing challenge {} ({} {}) claimed for user {}", challenge.id, challenge.kind, challenge.client, connection.userId)
        return PairingClaim.Claimed(connection.userId, challenge.kind, token, connectionService.info(connection), challenge.sealedBundle)
    }

    /** Pending codes past their lifetime free their code, bundles past the claim grace go; rows older than a day are removed. */
    @Scheduled(cron = "15 * * * * *", zone = "Europe/Moscow")
    fun expireChallenges() {
        try {
            val now = clock.instant()
            challenges.expireBefore(now)
            challenges.dropBundlesExpiredBefore(now.minus(CLAIM_GRACE))
            challenges.deleteCreatedBefore(now.minus(RETENTION))
        } catch (error: Exception) {
            // Spring's default scheduled-task logger includes unsafe exception messages and causes.
            log.error("Pairing expiry failed: {}", SafeDiagnostics.describe(error), error)
        }
    }

    private fun save(
        kind: PairKind,
        client: ClientType,
        commitment: ByteArray,
        name: String,
        version: String?,
        platform: String?,
        scopes: Set<ConnectionScope>,
        clientIp: String,
        connectionId: UUID?,
        now: Instant,
    ): CreatedChallenge {
        val claimSecret = ConnectionTokens.random()
        val challenge = challenges.save(
            PairChallengeEntity(
                kind = kind,
                client = client,
                code = freeCode(),
                claimSecretHash = ConnectionTokens.sha256(claimSecret),
                keyCommitment = commitment,
                name = name,
                version = version,
                platform = platform,
                scopes = ConnectionScope.format(scopes),
                clientIp = clientIp.take(64),
                createdAt = now,
                expiresAt = now.plus(CODE_TTL),
                connectionId = connectionId,
            ),
        )
        log.info("Pairing challenge {} ({} {}) created", challenge.id, kind, client)
        return CreatedChallenge(challenge.id, challenge.code, claimSecret, challenge.expiresAt)
    }

    /** `CONNECT` needs the client type on (403) and a free slot (409); `RENEW` only the live connection's owner. */
    private fun requireTarget(userId: UUID, challenge: PairChallengeEntity) {
        when (challenge.kind) {
            PairKind.CONNECT -> {
                if (!access.isEnabled(userId, challenge.client)) {
                    throw PermissionDeniedException("This kind of client is turned off for this account")
                }
                if (connections.countLive(userId, challenge.client) >= config.maxPerClient) {
                    throw BusinessRuleException("At most ${config.maxPerClient} connections of this kind; disconnect one first")
                }
            }

            PairKind.RENEW -> {
                val connection = challenge.connectionId?.let { connections.findById(it).orElse(null) }
                if (connection == null || connection.userId != userId || connection.revokedAt != null) throw notFound()
            }
        }
    }

    private fun requireSession(itmo: ItmoSession, now: Instant) {
        if (!SID_PATTERN.matches(itmo.sid)) throw InvalidRequestDataException("itmo.sid is not an ITMO.ID session id")
        if (!itmo.expiresAt.isAfter(now) || itmo.expiresAt.isAfter(now.plus(MAX_SESSION))) {
            throw InvalidRequestDataException("itmo.expiresAt must be within ${MAX_SESSION.toDays()} days from now")
        }
    }

    /** A code no pending challenge holds. */
    private fun freeCode(): String {
        repeat(CODE_ATTEMPTS) {
            val code = String(CharArray(CODE_LENGTH) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] })
            if (!challenges.existsByCodeAndStatus(code, PairStatus.PENDING)) return code
        }
        throw BusinessRuleException("No free pairing code, try again")
    }

    private fun notFound() = NotFoundException("Pairing request not found or expired")

    companion object {
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 8
        val CODE_TTL: Duration = Duration.ofMinutes(2)

        /** A code lives this long after its first open: the phone signs in at ITMO.ID in between. */
        val OPENED_TTL: Duration = Duration.ofMinutes(10)

        /** A client claiming every second or two may see an approval made in the code's last moment. */
        val CLAIM_GRACE: Duration = Duration.ofMinutes(1)
        val RATE_WINDOW: Duration = Duration.ofMinutes(10)
        val RETENTION: Duration = Duration.ofDays(1)

        /** An ITMO.ID session lives 90 days from its sign-in. */
        val MAX_SESSION: Duration = Duration.ofDays(91)
        const val MAX_UNCLAIMED_PER_WINDOW = 10
        const val COMMITMENT_BYTES = 32

        /** HPKE `enc` (32 bytes) and the AEAD tag (16 bytes) at least. */
        const val MIN_BUNDLE = 48
        const val MAX_BUNDLE = 16_384
        const val MAX_NAME = 60
        const val MAX_VERSION = 40
        const val MAX_PLATFORM = 16
        const val RENEWALS_PER_HOUR = 10
        const val OPENS_PER_WINDOW = 30
        const val DECISIONS_PER_HOUR = 20
        private const val CODE_ATTEMPTS = 5
        private val CODE_PATTERN = Regex("^[$CODE_ALPHABET]{$CODE_LENGTH}$")
        private val SID_PATTERN = Regex("^[A-Za-z0-9._:-]{1,64}$")
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
        fun text(value: String, max: Int, field: String): String? {
            val trimmed = value.trim()
            if (trimmed.length > max || trimmed.any(Char::isISOControl)) {
                throw InvalidRequestDataException("$field must be at most $max characters without control characters")
            }
            return trimmed.ifEmpty { null }
        }
    }
}
