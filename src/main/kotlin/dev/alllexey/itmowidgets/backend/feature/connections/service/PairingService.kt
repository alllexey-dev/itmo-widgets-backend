package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientAuth
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientStatus
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionClient
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionEntity
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.model.Grants
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairChallengeEntity
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairKind
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairStatus
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairingStep
import dev.alllexey.itmowidgets.backend.feature.connections.model.RequestedGrant
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionRepository
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.PairChallengeRepository
import dev.alllexey.itmowidgets.backend.feature.connections.web.ApprovePairingRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.ConnectionInfo
import dev.alllexey.itmowidgets.backend.feature.connections.web.CreateChallengeRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.CreatedChallenge
import dev.alllexey.itmowidgets.backend.feature.connections.web.GrantRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.ItmoSession
import dev.alllexey.itmowidgets.backend.feature.connections.web.PairingGrant
import dev.alllexey.itmowidgets.backend.feature.connections.web.PairingPreview
import dev.alllexey.itmowidgets.backend.feature.connections.web.RenewChallengeRequest
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.platform.error.TooManyRequestsException
import dev.alllexey.itmowidgets.backend.platform.error.UpdateRequiredException
import dev.alllexey.itmowidgets.backend.platform.metrics.DailyCounters
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
 * opens and approves it (with the HPKE bundle and the client's ITMO.ID session when it grants an ITMO grant), and the
 * client claims it exactly once with its claim secret. What a client may request comes from its registry entry
 * ([ConnectionClientRegistry]). A protocol-1 request (`client`, `scopes`) maps to its seeded entry and the grants the
 * released clients always got; a protocol-2 challenge (`grants`, or a client of kind `app`) opens only in an app that
 * says `?protocol=2`. Backend never sees a key, a pairing secret or an ITMO token; logs carry ids, kinds and client
 * types only. Each step of a `CONNECT` pairing is counted per day ([PairingStep]).
 */
@Service
class PairingService(
    private val challenges: PairChallengeRepository,
    private val connections: ConnectionRepository,
    private val connectionService: ConnectionService,
    private val access: ClientAccessService,
    private val registry: ConnectionClientRegistry,
    private val users: UserRepository,
    private val config: ConnectionsConfig,
    private val texts: ScopeTexts,
    private val clock: Clock,
    counters: DailyCounters,
) {
    private val steps = counters.counter(PairingStep.METRIC)
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
        val client = pairingClient(request)
        if (request.grants != null && request.scopes != null) throw InvalidRequestDataException("Send grants or scopes, not both")
        val protocol = if (request.grants != null || client.kind == ClientType.APP) PROTOCOL_2 else PROTOCOL_1
        val requested = if (request.grants !=
            null
        ) {
            requestedGrants(client, request.grants, itmoOnly = false)
        } else {
            legacyGrants(client, request.scopes)
        }
        if (requested.none {
                Grants.scopeOf(it.id) != null
            }
        ) {
            throw InvalidRequestDataException("A connection requests at least one widgets grant")
        }
        client.policyViolation(requested.map { it.id })?.let { throw InvalidRequestDataException(it) }
        val now = clock.instant()
        if (challenges.countByClientIpSince(clientIp, now.minus(RATE_WINDOW)) >= MAX_UNCLAIMED_PER_WINDOW) {
            throw TooManyRequestsException("Too many pairing codes, try again later")
        }
        val scopes = requested.mapNotNull { Grants.scopeOf(it.id) }.toSet()
        return save(PairKind.CONNECT, client, commitment, name, version, platform, scopes, requested, protocol, clientIp, null, now)
            .also { count(PairingStep.CREATED, PairKind.CONNECT, client.kind) }
    }

    /**
     * `POST /api/client/pair/renew`: a new approval for a live connection and, for a client with ITMO grants, a new
     * ITMO.ID session. `grants` (only `itmo:*`) makes it protocol 2; without them a released client gets what it always
     * got: the refresh grant for an agent or MCP server, nothing ITMO for the web.
     */
    @Transactional
    fun renew(connectionId: UUID, request: RenewChallengeRequest, clientIp: String): CreatedChallenge {
        val commitment = decode(request.keyCommitment, COMMITMENT_BYTES, COMMITMENT_BYTES, "keyCommitment")
        val connection = connections.findById(connectionId).orElse(null)?.takeIf { it.revokedAt == null }
            ?: throw NotFoundException("Connection not found")
        if (!renewLimiter.tryAcquire(connectionId)) throw TooManyRequestsException("Too many renewals, try again later")
        val client = registry.get(connection.registryId)
        val protocol = if (request.grants != null || client.kind == ClientType.APP) PROTOCOL_2 else PROTOCOL_1
        val requested = when {
            request.grants != null -> requestedGrants(client, request.grants, itmoOnly = true)

            client.kind == ClientType.WEB -> emptyList()

            protocol == PROTOCOL_1 -> listOf(RequestedGrant(Grants.ITMO_REFRESH, true))

            else -> connection.itmoGrants.toList().ifEmpty { listOf(Grants.ITMO_REFRESH) }
                .mapNotNull { id -> client.grant(id)?.let { RequestedGrant(id, it.required) } }
        }
        client.policyViolation(requested.map { it.id })?.let { throw InvalidRequestDataException(it) }
        return save(
            PairKind.RENEW,
            client,
            commitment,
            connection.name,
            connection.version,
            connection.platform,
            connection.scopeSet,
            requested,
            protocol,
            clientIp,
            connection.id,
            clock.instant(),
        )
    }

    /**
     * The first account that opens a code owns it; anyone else gets 404, as for an unknown code. [protocol] is the
     * highest pairing protocol the app knows (null: 1); a protocol-2 challenge needs 2, else 409 `update_required`. An
     * app that knows 2 also gets the client app and its requested grants.
     */
    @Transactional
    fun open(userId: UUID, code: String, protocol: Int? = null): PairingPreview {
        if (!openLimiter.tryAcquire(userId)) throw TooManyRequestsException("Too many codes opened, try again later")
        val now = clock.instant()
        val challenge = code.trim().uppercase().takeIf(CODE_PATTERN::matches)?.let { challenges.findPendingByCode(it, now) }
            ?.takeIf { it.openedBy == null || it.openedBy == userId }
            ?: throw notFound()
        val knowsGrants = protocol != null && protocol >= PROTOCOL_2
        if (challenge.protocol.toInt() == PROTOCOL_2 && !knowsGrants) {
            throw UpdateRequiredException("Update the app to connect this client")
        }
        requireTarget(userId, challenge)
        if (challenge.openedBy == null) {
            if (challenges.open(challenge.id, userId, now, now.plus(OPENED_TTL)) != 1) throw notFound()
            count(PairingStep.OPENED, challenge.kind, challenge.client)
        }
        log.info("Pairing challenge {} ({} {}) opened by user {}", challenge.id, challenge.kind, challenge.client, userId)
        val opened = challenges.findById(challenge.id).orElseThrow(::notFound)
        val preview = PairingPreview(
            opened.id,
            opened.kind,
            opened.client,
            opened.name,
            opened.version,
            opened.platform,
            opened.scopeSet.sorted().map { texts.info(it) },
            encode(opened.keyCommitment),
            opened.connectionId,
            opened.expiresAt,
        )
        return if (knowsGrants) withGrants(preview, opened) else preview
    }

    /**
     * Only the opener; a repeated approval is a no-op. A `CONNECT` creates the connection (its token comes at the
     * claim), a `RENEW` moves its approval time and, when the phone sealed ITMO grants, replaces its ITMO.ID session.
     * Protocol 1 takes `scopes`; protocol 2 takes `granted`, the requested grant ids the user chose.
     */
    @Transactional
    fun approve(userId: UUID, challengeId: UUID, request: ApprovePairingRequest): UUID {
        if (!decisionLimiter.tryAcquire(userId)) throw TooManyRequestsException("Too many pairing decisions, try again later")
        val challenge = challenges.findById(challengeId).orElse(null)?.takeIf { it.openedBy == userId } ?: throw notFound()
        if (challenge.status == PairStatus.APPROVED) return checkNotNull(challenge.connectionId)
        if (challenge.status != PairStatus.PENDING) throw notFound()
        val requested = requestedOf(challenge)
        val decision = if (challenge.protocol.toInt() ==
            PROTOCOL_2
        ) {
            grantedDecision(challenge, requested, request)
        } else {
            legacyDecision(challenge, requested, request)
        }
        val itmoGranted = decision.itmoGrants.isNotEmpty()
        val bundle = request.sealedBundle?.let { decode(it, MIN_BUNDLE, MAX_BUNDLE, "sealedBundle") }
        if ((bundle != null) != itmoGranted) {
            throw InvalidRequestDataException(if (itmoGranted) "sealedBundle is required" else "No ITMO grant, so no sealedBundle")
        }
        val itmo = request.itmo
        if ((itmo != null) !=
            itmoGranted
        ) {
            throw InvalidRequestDataException(if (itmoGranted) "itmo is required" else "No ITMO grant, so no itmo")
        }
        val now = clock.instant()
        itmo?.let { requireSession(it, now) }
        users.lockById(userId) ?: throw NotFoundException("User not found")
        requireTarget(userId, challenge)
        registry.get(challenge.registryId).policyViolation(decision.itmoGrants)?.let { throw InvalidRequestDataException(it) }
        val connectionId = when (challenge.kind) {
            PairKind.CONNECT -> connections.save(
                ConnectionEntity(
                    userId = userId,
                    client = challenge.client,
                    name = challenge.name,
                    version = challenge.version,
                    platform = challenge.platform,
                    scopes = ConnectionScope.format(decision.scopes ?: challenge.scopeSet),
                    createdAt = now,
                    approvedAt = now,
                    itmoSid = itmo?.sid,
                    itmoExpiresAt = itmo?.expiresAt,
                    clientId = challenge.registryId,
                    itmoGrants = decision.itmoGrants.toTypedArray(),
                ),
            ).id

            PairKind.RENEW -> {
                val connection = connections.findById(checkNotNull(challenge.connectionId)).orElseThrow(::notFound)
                connection.approvedAt = now
                if (itmo != null) {
                    connection.itmoSid = itmo.sid
                    connection.itmoExpiresAt = itmo.expiresAt
                    connection.itmoEndedAt = null
                    connection.itmoGrants = decision.itmoGrants.toTypedArray()
                }
                connection.id
            }
        }
        if (challenges.approve(challengeId, userId, now, bundle, connectionId) != 1) throw notFound()
        count(PairingStep.APPROVED, challenge.kind, challenge.client)
        log.info("Pairing challenge {} ({} {}) approved by user {}", challengeId, challenge.kind, challenge.client, userId)
        return connectionId
    }

    /** What an approval stores: the scopes of a new connection (null keeps the requested ones) and the ITMO grants sealed. */
    private class Decision(val scopes: Set<ConnectionScope>?, val itmoGrants: List<String>)

    /** Protocol 1 as released: `scopes` narrow a new connection, the ITMO grants are the requested ones. */
    private fun legacyDecision(challenge: PairChallengeEntity, requested: List<RequestedGrant>, request: ApprovePairingRequest): Decision {
        if (request.granted != null) throw InvalidRequestDataException("granted is only for a protocol-2 pairing")
        val scopes = request.scopes?.toSet()
        if (scopes != null && (challenge.kind == PairKind.RENEW || scopes.isEmpty() || !challenge.scopeSet.containsAll(scopes))) {
            throw InvalidRequestDataException("scopes are a non-empty subset of the requested ones, and only for a new connection")
        }
        return Decision(scopes, requested.map { it.id }.filter(Grants::isItmo))
    }

    /**
     * Protocol 2: `granted` is a subset of the requested ids holding every required one and, for a new connection, at
     * least one `widgets:*` grant; the scopes are its `widgets:*` part, the ITMO grants its `itmo:*` part.
     */
    private fun grantedDecision(challenge: PairChallengeEntity, requested: List<RequestedGrant>, request: ApprovePairingRequest): Decision {
        val granted = request.granted?.toSet() ?: throw InvalidRequestDataException("granted is required")
        if (request.scopes != null) throw InvalidRequestDataException("scopes are not sent with granted")
        if (!requested.map { it.id }.containsAll(granted)) throw InvalidRequestDataException("granted must be among the requested grants")
        if (requested.any { it.required && it.id !in granted }) throw InvalidRequestDataException("granted must hold every required grant")
        val chosen = requested.filter { it.id in granted }
        val scopes = chosen.mapNotNull { Grants.scopeOf(it.id) }.toSet()
        if (challenge.kind == PairKind.CONNECT && scopes.isEmpty()) {
            throw InvalidRequestDataException("A new connection needs at least one widgets grant")
        }
        return Decision(scopes.takeIf { challenge.kind == PairKind.CONNECT }, chosen.map { it.id }.filter(Grants::isItmo))
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
        count(PairingStep.CLAIMED, challenge.kind, challenge.client)
        return PairingClaim.Claimed(connection.userId, challenge.kind, token, connectionService.info(connection), challenge.sealedBundle)
    }

    /** Pending codes past their lifetime free their code, bundles past the claim grace go; rows older than a day are removed. */
    @Scheduled(cron = "15 * * * * *", zone = "Europe/Moscow")
    fun expireChallenges() {
        try {
            val now = clock.instant()
            challenges.expireBefore(now).forEach { steps.increment(PairingStep.EXPIRED.name, it.client, it.count) }
            challenges.dropBundlesExpiredBefore(now.minus(CLAIM_GRACE))
            challenges.deleteCreatedBefore(now.minus(RETENTION))
        } catch (error: Exception) {
            // Spring's default scheduled-task logger includes unsafe exception messages and causes.
            log.error("Pairing expiry failed: {}", SafeDiagnostics.describe(error), error)
        }
    }

    private fun save(
        kind: PairKind,
        client: ConnectionClient,
        commitment: ByteArray,
        name: String,
        version: String?,
        platform: String?,
        scopes: Set<ConnectionScope>,
        requested: List<RequestedGrant>,
        protocol: Int,
        clientIp: String,
        connectionId: UUID?,
        now: Instant,
    ): CreatedChallenge {
        val claimSecret = ConnectionTokens.random()
        val challenge = challenges.save(
            PairChallengeEntity(
                kind = kind,
                client = client.kind,
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
                clientId = client.id,
                requestedGrants = RequestedGrant.format(requested),
                protocol = protocol.toShort(),
            ),
        )
        log.info("Pairing challenge {} ({} {}, protocol {}) created", challenge.id, kind, client.kind, protocol)
        return CreatedChallenge(challenge.id, challenge.code, claimSecret, challenge.expiresAt)
    }

    /** Counts [step] of a `CONNECT` pairing, after the commit; renewals are not counted. */
    private fun count(step: PairingStep, kind: PairKind, client: ClientType) {
        if (kind == PairKind.CONNECT) steps.increment(step.name, client.name)
    }

    /**
     * `CONNECT` needs the client kind on and the client active (403) and a free slot of the client (409); `RENEW` only
     * the live connection's owner.
     */
    private fun requireTarget(userId: UUID, challenge: PairChallengeEntity) {
        when (challenge.kind) {
            PairKind.CONNECT -> {
                val client = registry.get(challenge.registryId)
                if (client.status != ClientStatus.ACTIVE || !access.isEnabled(userId, challenge.client)) {
                    throw PermissionDeniedException("This kind of client is turned off for this account")
                }
                val max = client.maxPerUser ?: config.maxPerClient
                if (connections.countLiveOfClient(userId, client.id, ClientType.ofLegacyClientId(client.id)) >= max) {
                    throw BusinessRuleException("At most $max connections of this client; disconnect one first")
                }
            }

            PairKind.RENEW -> {
                val connection = challenge.connectionId?.let { connections.findById(it).orElse(null) }
                if (connection == null || connection.userId != userId || connection.revokedAt != null) throw notFound()
            }
        }
    }

    /** The registry entry a create names: exactly one of a kind (its seeded entry) and an id; active and public. */
    private fun pairingClient(request: CreateChallengeRequest): ConnectionClient {
        val id = when {
            request.client != null && request.clientId != null -> throw InvalidRequestDataException("Send client or clientId, not both")

            request.clientId != null -> request.clientId

            request.client != null ->
                request.client.legacyClientId
                    ?: throw InvalidRequestDataException("This kind of client sends clientId")

            else -> throw InvalidRequestDataException("client or clientId is required")
        }
        val client = registry.find(id) ?: throw InvalidRequestDataException("Unknown clientId")
        if (client.status != ClientStatus.ACTIVE) throw PermissionDeniedException("This client is suspended")
        if (client.auth != ClientAuth.PUBLIC) throw InvalidRequestDataException("Only public clients pair yet")
        return client
    }

    /**
     * The grants a protocol-2 request asks for, in registry order: each a grant of [client] at most once (only `itmo:*`
     * when [itmoOnly]); `required` may only add a requirement, and every required registry grant is requested.
     */
    private fun requestedGrants(client: ConnectionClient, items: List<GrantRequest>, itmoOnly: Boolean): List<RequestedGrant> {
        val asked = items.associateBy { it.id }
        if (asked.size != items.size) throw InvalidRequestDataException("Each grant at most once")
        if (itmoOnly && !asked.keys.all(Grants::isItmo)) throw InvalidRequestDataException("A renewal requests only itmo grants")
        if (!asked.keys.all {
                client.grant(it) != null
            }
        ) {
            throw InvalidRequestDataException("A requested grant is not registered for this client")
        }
        return client.grants
            .filter { it.id in asked || (it.required && (!itmoOnly || Grants.isItmo(it.id))) }
            .map { RequestedGrant(it.id, it.required || asked[it.id]?.required == true) }
    }

    /**
     * A protocol-1 request: `scopes` within the client's `widgets:*` grants (its default ones when absent or empty) and,
     * for an agent or MCP server, the refresh grant, required, as the released clients always got.
     */
    private fun legacyGrants(client: ConnectionClient, scopes: List<ConnectionScope>?): List<RequestedGrant> {
        val chosen = scopes?.toSet()?.takeIf { it.isNotEmpty() }
            ?: client.grants.filter { it.requestedByDefault }.mapNotNull { Grants.scopeOf(it.id) }.toSet()
        if (!client.scopes.containsAll(
                chosen,
            )
        ) {
            throw InvalidRequestDataException("scopes must be within ${client.kind.wire}'s allowed scopes")
        }
        val widgets = client.grants.filter { Grants.scopeOf(it.id) in chosen }.map { RequestedGrant(it.id, it.required) }
        return widgets + legacyItmo(client.kind)
    }

    private fun legacyItmo(kind: ClientType): List<RequestedGrant> =
        if (kind == ClientType.AGENT || kind == ClientType.MCP) listOf(RequestedGrant(Grants.ITMO_REFRESH, true)) else emptyList()

    /** The stored request; a challenge from before V39 reads as the protocol-1 mapping of its scopes. */
    private fun requestedOf(challenge: PairChallengeEntity): List<RequestedGrant> = challenge.requestedGrants?.let(RequestedGrant::parse)
        ?: run {
            val widgets = if (challenge.kind == PairKind.CONNECT) {
                val client = registry.get(challenge.registryId)
                challenge.scopeSet.sorted().map { RequestedGrant(Grants.widgets(it), client.grant(Grants.widgets(it))?.required ?: false) }
            } else {
                emptyList()
            }
            widgets + legacyItmo(challenge.client)
        }

    /** The protocol-2 part of a preview: the client app and its requested grants with their texts. */
    private fun withGrants(preview: PairingPreview, challenge: PairChallengeEntity): PairingPreview {
        val client = registry.get(challenge.registryId)
        val held = challenge.connectionId?.takeIf { challenge.kind == PairKind.RENEW }
            ?.let { connections.findById(it).orElse(null)?.itmoGrants?.toSet() }
        val grants = requestedOf(challenge).map { grant ->
            val scope = Grants.scopeOf(grant.id)?.let { texts.info(it) }
            PairingGrant(
                grant.id,
                grant.required,
                scope?.title,
                scope?.description,
                client.grant(grant.id)?.purpose,
                if (held != null && Grants.isItmo(grant.id)) grant.id in held else null,
            )
        }
        return preview.copy(protocol = challenge.protocol.toInt(), app = ConnectionService.appInfo(client), grants = grants)
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
        const val PROTOCOL_1 = 1
        const val PROTOCOL_2 = 2
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
