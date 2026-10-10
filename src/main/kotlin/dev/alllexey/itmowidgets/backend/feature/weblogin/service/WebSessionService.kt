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

/** Browser sessions behind the `iw_session` cookie, limited by [WebSessionConfig] and revoked on logout. */
@Service
class WebSessionService(private val sessions: WebSessionRepository, private val config: WebSessionConfig, private val clock: Clock) {
    /** Returns the raw cookie token; only its hash is stored. */
    @Transactional
    fun issue(userId: UUID, userAgent: String?): String {
        val token = WebTokens.random()
        val now = clock.instant()
        sessions.save(
            WebSessionEntity(
                userId = userId,
                tokenHash = WebTokens.sha256(token),
                userAgent = userAgent?.take(300),
                createdAt = now,
                lastSeenAt = now,
                expiresAt = now.plus(config.maxLifetime),
            ),
        )
        return token
    }

    /**
     * The session, extending its idle window, or null when unknown, revoked, idle or past its lifetime.
     * `lastSeenAt` is written at most once per [LAST_SEEN_STEP], so a busy session does not update its row on every request.
     */
    @Transactional
    fun resolve(token: String): ActiveWebSession? {
        if (!WebTokens.isWellFormed(token)) return null
        val now = clock.instant()
        val session = sessions.findActiveByTokenHash(WebTokens.sha256(token), now) ?: return null
        if (!now.isBefore(session.lastSeenAt.plus(config.idleTimeout))) return null
        if (!now.isBefore(session.lastSeenAt.plus(LAST_SEEN_STEP))) session.lastSeenAt = now
        return ActiveWebSession(
            session.userId,
            signedInAt = session.createdAt,
            freshForAdmin = !now.isAfter(session.createdAt.plus(config.adminMaxAge)),
        )
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
        /** Counted from `expiresAt`; longer than any allowed [WebSessionConfig.maxLifetime]. */
        val RETENTION: Duration = Duration.ofDays(90)
        val LAST_SEEN_STEP: Duration = Duration.ofMinutes(5)
        private val logger = LoggerFactory.getLogger(WebSessionService::class.java)
    }
}

/** A live browser session: its user, when it was signed in and whether that was within [WebSessionConfig.adminMaxAge]. */
data class ActiveWebSession(val userId: UUID, val signedInAt: Instant, val freshForAdmin: Boolean)

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
