package dev.alllexey.itmowidgets.backend.feature.agent.service

import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentScope
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentTokenEntity
import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentTokenOrigin
import dev.alllexey.itmowidgets.backend.feature.agent.persistence.AgentTokenRepository
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebTokens
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.platform.error.TooManyRequestsException
import dev.alllexey.itmowidgets.backend.platform.ratelimit.WindowLimiter
import dev.alllexey.itmowidgets.backend.platform.security.AgentAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.AgentDecision
import dev.alllexey.itmowidgets.backend.platform.security.AgentTokenFilter
import dev.alllexey.itmowidgets.backend.platform.security.AgentTokenResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A freshly issued token: the raw value, shown once, and its row. */
data class IssuedAgentToken(val token: String, val entity: AgentTokenEntity)

/** Personal agent tokens: issue, resolve per request, heartbeat, revoke. Only SHA-256 hashes are stored. */
@Service
class AgentTokenService(
    private val tokens: AgentTokenRepository,
    private val access: AgentAccessService,
    private val users: UserRepository,
    private val config: AgentConfig,
    private val clock: Clock,
) : AgentTokenResolver {
    private val eventsLimiter = WindowLimiter(EVENTS_PER_MINUTE, Duration.ofMinutes(1), clock)
    private val routesLimiter = WindowLimiter(ROUTES_PER_MINUTE, Duration.ofMinutes(1), clock)

    /**
     * Issues a token after the access, count and daily checks, under the owner's row lock so two requests cannot both
     * take the last slot.
     */
    @Transactional
    fun issue(userId: UUID, name: String, scopes: Set<AgentScope>, origin: AgentTokenOrigin): IssuedAgentToken {
        val cleanName = validName(name)
        if (scopes.isEmpty()) throw InvalidRequestDataException("An agent token needs at least one scope")
        users.lockById(userId) ?: throw NotFoundException("User not found")
        requireCanIssue(userId)
        val now = clock.instant()
        if (tokens.countByUserIdAndCreatedAtGreaterThanEqual(userId, now.minus(Duration.ofDays(1))) >= MAX_CREATED_PER_DAY) {
            throw TooManyRequestsException("Too many agent tokens today, try again tomorrow")
        }
        return mint(userId, cleanName, scopes, origin, now)
    }

    /**
     * The token of an approved `AGENT` pairing, minted at its claim. Access and the free slot were checked when the phone
     * approved; the claim must not fail after the challenge is marked claimed.
     */
    @Transactional
    fun issuePaired(userId: UUID, name: String, scopes: Set<AgentScope>): IssuedAgentToken =
        mint(userId, validName(name), scopes, AgentTokenOrigin.PAIR, clock.instant())

    private fun mint(userId: UUID, cleanName: String, scopes: Set<AgentScope>, origin: AgentTokenOrigin, now: Instant): IssuedAgentToken {
        val secret = WebTokens.random()
        val entity = tokens.save(
            AgentTokenEntity(
                userId = userId,
                name = cleanName,
                tokenHash = WebTokens.sha256(AgentTokenFilter.PREFIX + secret),
                tokenHint = secret.take(HINT_LENGTH),
                scopes = AgentScope.format(scopes),
                createdVia = origin,
                createdAt = now,
            ),
        )
        log.info("Agent token {} issued for user {} via {}", entity.id, userId, origin)
        return IssuedAgentToken(AgentTokenFilter.PREFIX + secret, entity)
    }

    /** Access on and a free slot; checked again by [issue]. */
    @Transactional(readOnly = true)
    fun requireCanIssue(userId: UUID) {
        if (!access.isEnabled(userId)) throw PermissionDeniedException("Self-hosted clients are turned off for this account")
        if (tokens.countActive(userId) >= config.maxTokens) {
            throw BusinessRuleException("At most ${config.maxTokens} connected agents; disconnect one first")
        }
    }

    @Transactional
    override fun resolve(token: String): AgentAuthentication? {
        val secret = token.removePrefix(AgentTokenFilter.PREFIX)
        if (!token.startsWith(AgentTokenFilter.PREFIX) || !WebTokens.isWellFormed(secret)) return null
        val entity = tokens.findActiveByHash(WebTokens.sha256(token)) ?: return null
        val now = clock.instant()
        if (!(entity.lastUsedAt ?: entity.createdAt).plus(config.idleRevoke).isAfter(now)) {
            tokens.revoke(entity.id, entity.userId, now)
            log.info("Agent token {} of user {} revoked after {} unused", entity.id, entity.userId, config.idleRevoke)
            return null
        }
        tokens.touch(entity.id, now, now.minus(LAST_USED_STEP))
        return AgentAuthentication(entity.userId, entity.id, entity.name, entity.scopeSet.map { it.wire }.toSet())
    }

    override fun decide(authentication: AgentAuthentication, method: String, path: String): AgentDecision {
        val route = AgentRoutePolicy.permitted(authentication.scopes, method, path)
        if (route?.selfRevoke != true && !access.isEnabled(authentication.userId)) return AgentDecision.DISABLED
        if (route == null) return AgentDecision.DENIED
        val limiter = if (route.events) eventsLimiter else routesLimiter
        return if (limiter.tryAcquire(authentication.tokenId)) AgentDecision.ALLOWED else AgentDecision.RATE_LIMITED
    }

    /** The poll's heartbeat: time (at most once per [POLL_STEP]), the ITMO session state (at once) and the cursor. */
    @Transactional
    fun recordPoll(tokenId: UUID, after: Long, itmoOk: Boolean) {
        val now = clock.instant()
        tokens.recordPoll(tokenId, now, now.minus(POLL_STEP), itmoOk, after)
    }

    @Transactional(readOnly = true)
    fun activeTokens(userId: UUID): List<AgentTokenEntity> = tokens.findActiveByUser(userId)

    /** Returns false for an unknown, foreign or already revoked token. */
    @Transactional
    fun revoke(userId: UUID, tokenId: UUID): Boolean {
        val revoked = tokens.revoke(tokenId, userId, clock.instant()) == 1
        if (revoked) log.info("Agent token {} of user {} revoked", tokenId, userId)
        return revoked
    }

    @Transactional(readOnly = true)
    fun find(tokenId: UUID): AgentTokenEntity? = tokens.findById(tokenId).orElse(null)

    private fun validName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH || trimmed.any(Char::isISOControl)) {
            throw InvalidRequestDataException("Name must be 1-$MAX_NAME_LENGTH characters without control characters")
        }
        return trimmed
    }

    companion object {
        const val MAX_NAME_LENGTH = 60
        const val MAX_CREATED_PER_DAY = 10
        const val EVENTS_PER_MINUTE = 120
        const val ROUTES_PER_MINUTE = 60
        private const val HINT_LENGTH = 8
        val LAST_USED_STEP: Duration = Duration.ofMinutes(5)
        val POLL_STEP: Duration = Duration.ofMinutes(1)
        private val log = LoggerFactory.getLogger(AgentTokenService::class.java)
    }
}
