package dev.alllexey.itmowidgets.backend.feature.weblogin.service

import dev.alllexey.itmowidgets.backend.feature.weblogin.model.WebSessionEntity
import dev.alllexey.itmowidgets.backend.feature.weblogin.persistence.WebSessionRepository
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * Browser sessions behind the `iw_session` (access) and `iw_refresh` cookies, limited by [WebSessionConfig] and revoked
 * on logout or from the connections list. A refresh rotates both tokens within the same row, so the connection keeps its id.
 */
@Service
class WebSessionService(private val sessions: WebSessionRepository, private val config: WebSessionConfig, private val clock: Clock) {
    /** Returns the raw cookie token; only its hash is stored. */
    @Transactional
    fun issue(userId: UUID, userAgent: String?): String = issueSession(userId, userAgent).token

    /** The raw cookie tokens and the session's id, the id the connections list shows. */
    @Transactional
    fun issueSession(userId: UUID, userAgent: String?): IssuedWebSession {
        val token = WebTokens.random()
        val refreshToken = WebTokens.random()
        val now = clock.instant()
        val session = sessions.save(
            WebSessionEntity(
                userId = userId,
                tokenHash = WebTokens.sha256(token),
                userAgent = userAgent?.take(300),
                createdAt = now,
                lastSeenAt = now,
                expiresAt = now.plus(config.idleTimeout),
                accessExpiresAt = now.plus(config.accessTtl),
                refreshHash = WebTokens.sha256(refreshToken),
            ),
        )
        return IssuedWebSession(session.id, token, refreshToken)
    }

    /**
     * The session, extending its idle window, or null when unknown, revoked, idle, past its lifetime or its access token's.
     * `lastSeenAt` is written at most once per [LAST_SEEN_STEP], so a busy session does not update its row on every request.
     */
    @Transactional
    fun resolve(token: String): ActiveWebSession? {
        if (!WebTokens.isWellFormed(token)) return null
        val now = clock.instant()
        val session = sessions.findActiveByTokenHash(WebTokens.sha256(token), now) ?: return null
        if (session.accessExpiresAt?.isAfter(now) == false || isIdle(session, now)) return null
        if (!now.isBefore(session.lastSeenAt.plus(LAST_SEEN_STEP))) session.lastSeenAt = now
        return ActiveWebSession(
            session.userId,
            sessionId = session.id,
            signedInAt = session.createdAt,
            freshForAdmin = !now.isAfter(session.createdAt.plus(config.adminMaxAge)),
        )
    }

    /**
     * Rotates the access and refresh tokens of the session holding [refreshToken]. The previous refresh token is answered
     * [WebSessionRefresh.AlreadyRotated] for [ROTATION_GRACE] (another tab or a lost response already rotated it, the
     * browser holds or gets the new cookies); after that it is a replay and revokes the session.
     */
    @Transactional
    fun refresh(refreshToken: String): WebSessionRefresh {
        if (!WebTokens.isWellFormed(refreshToken)) return WebSessionRefresh.Rejected
        val hash = WebTokens.sha256(refreshToken)
        val now = clock.instant()
        val session = sessions.lockByRefreshHash(hash) ?: return WebSessionRefresh.Rejected
        if (session.revokedAt != null || !now.isBefore(session.expiresAt) || isIdle(session, now)) return WebSessionRefresh.Rejected
        if (session.refreshHash != hash) {
            if (now.isBefore(checkNotNull(session.rotatedAt).plus(ROTATION_GRACE))) return WebSessionRefresh.AlreadyRotated
            session.revokedAt = now
            logger.warn("Web session {} revoked: a rotated refresh token was used again", session.id)
            return WebSessionRefresh.Rejected
        }
        val token = WebTokens.random()
        val nextRefresh = WebTokens.random()
        session.tokenHash = WebTokens.sha256(token)
        session.accessExpiresAt = now.plus(config.accessTtl)
        session.previousRefreshHash = hash
        session.refreshHash = WebTokens.sha256(nextRefresh)
        session.rotatedAt = now
        session.lastSeenAt = now
        session.expiresAt = now.plus(config.idleTimeout)
        return WebSessionRefresh.Rotated(IssuedWebSession(session.id, token, nextRefresh))
    }

    @Transactional
    fun revoke(token: String) {
        if (WebTokens.isWellFormed(token)) sessions.revokeByTokenHash(WebTokens.sha256(token), clock.instant())
    }

    private fun isIdle(session: WebSessionEntity, now: Instant) = !now.isBefore(session.lastSeenAt.plus(config.idleTimeout))

    /** Ended sessions stay for [RETENTION] so admin statistics can count recent sign-ins. */
    @Scheduled(cron = "0 5 * * * *", zone = "Europe/Moscow")
    fun deleteExpired(): Int = try {
        sessions.deleteExpiredBefore(clock.instant().minus(RETENTION))
    } catch (error: Exception) {
        // Spring's default scheduled-task logger includes unsafe exception messages and causes.
        logger.error("Web session retention failed: {}", SafeDiagnostics.describe(error), error)
        0
    }

    companion object {
        /** Counted from `expiresAt`. */
        val RETENTION: Duration = Duration.ofDays(90)
        val LAST_SEEN_STEP: Duration = Duration.ofMinutes(5)

        /** How long the previous refresh token is answered without a rotation instead of being treated as a replay. */
        val ROTATION_GRACE: Duration = Duration.ofMinutes(2)
        private val logger = LoggerFactory.getLogger(WebSessionService::class.java)
    }
}

/**
 * A live browser session: its user, when it was signed in and whether that was within [WebSessionConfig.adminMaxAge].
 * [sessionId] marks the current row in the connections list.
 */
data class ActiveWebSession(val userId: UUID, val signedInAt: Instant, val freshForAdmin: Boolean, val sessionId: UUID? = null)

/** [token] is the access token, [refreshToken] rotates it. */
data class IssuedWebSession(val id: UUID, val token: String, val refreshToken: String)

sealed interface WebSessionRefresh {
    data class Rotated(val session: IssuedWebSession) : WebSessionRefresh

    /** The previous refresh token within the grace: nothing changes, no cookies are set. */
    data object AlreadyRotated : WebSessionRefresh

    /** Unknown, revoked, idle, expired or replayed: the browser signs in again. */
    data object Rejected : WebSessionRefresh
}

/** Random secrets for sessions and login polling, stored only as SHA-256. */
internal object WebTokens {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val tokenPattern = Regex("^[A-Za-z0-9_-]{43}$")

    fun random(): String = ByteArray(32).also(random::nextBytes).let(encoder::encodeToString)

    fun isWellFormed(token: String): Boolean = tokenPattern.matches(token)

    fun sha256(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    /** Constant-time comparison of a presented secret against a stored hash. */
    fun matches(token: String, hash: String): Boolean =
        MessageDigest.isEqual(sha256(token).toByteArray(Charsets.US_ASCII), hash.toByteArray(Charsets.US_ASCII))
}
