package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.exceptions.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.model.WebSessionEntity
import dev.alllexey.itmowidgets.backend.repositories.WebSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Browser sessions behind the `iw_session` cookie: 2 hours idle, 12 hours at most, revoked on logout. */
@Service
class WebSessionService(
    private val sessions: WebSessionRepository,
    private val clock: Clock,
) {
    /** Returns the raw cookie token; only its hash is stored. */
    @Transactional
    fun issue(userId: UUID, userAgent: String?): String {
        val token = WebTokens.random()
        val now = clock.instant()
        sessions.save(WebSessionEntity(userId = userId, tokenHash = WebTokens.sha256(token), userAgent = userAgent?.take(300),
            createdAt = now, lastSeenAt = now, expiresAt = now.plus(MAX_LIFETIME)))
        return token
    }

    /** The session's user, extending its idle window, or null when unknown, revoked, idle or past its lifetime. */
    @Transactional
    fun resolve(token: String): UUID? {
        if (!WebTokens.isWellFormed(token)) return null
        val now = clock.instant()
        val session = sessions.findActiveByTokenHash(WebTokens.sha256(token), now) ?: return null
        if (!now.isBefore(session.lastSeenAt.plus(IDLE_TIMEOUT))) return null
        session.lastSeenAt = now
        return session.userId
    }

    @Transactional
    fun revoke(token: String) {
        if (WebTokens.isWellFormed(token)) sessions.revokeByTokenHash(WebTokens.sha256(token), clock.instant())
    }

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
        val IDLE_TIMEOUT: Duration = Duration.ofHours(2)
        val MAX_LIFETIME: Duration = Duration.ofHours(12)
        val RETENTION: Duration = Duration.ofDays(30)
        private val logger = LoggerFactory.getLogger(WebSessionService::class.java)
    }
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
