package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.ClaimResult
import dev.alllexey.itmowidgets.backend.dto.CreatedChallenge
import dev.alllexey.itmowidgets.backend.dto.WebLoginPreview
import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.exceptions.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.exceptions.TooManyRequestsException
import dev.alllexey.itmowidgets.backend.model.WebLoginChallengeEntity
import dev.alllexey.itmowidgets.backend.model.WebLoginStatus
import dev.alllexey.itmowidgets.backend.repositories.WebLoginChallengeRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Phone-approved web login: a browser creates a code, a signed-in app approves it,
 * and the same browser claims one web session with the poll secret it received.
 */
@Service
class WebLoginService(
    private val challenges: WebLoginChallengeRepository,
    private val sessions: WebSessionService,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    @Transactional
    fun createChallenge(userAgent: String?, clientIp: String): CreatedChallenge {
        val now = clock.instant()
        if (challenges.countByClientIpSince(clientIp, now.minus(RATE_WINDOW)) >= MAX_UNAPPROVED_PER_WINDOW) {
            throw TooManyRequestsException("Too many login codes, try again later")
        }
        val pollSecret = WebTokens.random()
        val challenge = challenges.save(WebLoginChallengeEntity(code = freeCode(), pollSecretHash = WebTokens.sha256(pollSecret),
            userAgent = userAgent?.take(300), clientIp = clientIp.take(64), createdAt = now, expiresAt = now.plus(CODE_TTL)))
        return CreatedChallenge(challenge.id, challenge.code, pollSecret, challenge.expiresAt)
    }

    /** [userId] is the signed-in app user looking at the code; any user may approve a login for themselves. */
    @Transactional(readOnly = true)
    fun preview(@Suppress("UNUSED_PARAMETER") userId: UUID, code: String): WebLoginPreview {
        val normalized = code.trim().uppercase()
        val challenge = normalized.takeIf(CODE_PATTERN::matches)?.let { challenges.findPendingByCode(it, clock.instant()) }
            ?: throw NotFoundException("Login code not found or expired")
        return WebLoginPreview(challenge.id, challenge.userAgent, challenge.createdAt, challenge.expiresAt)
    }

    /** A repeated approval by the same user is a no-op; anything else not pending is gone. */
    @Transactional
    fun approve(userId: UUID, challengeId: UUID) {
        if (challenges.approve(challengeId, userId, clock.instant()) == 1) return
        val existing = challenges.findById(challengeId).orElse(null)
        if (existing?.status == WebLoginStatus.APPROVED && existing.approvedBy == userId) return
        throw NotFoundException("Login request not found or expired")
    }

    /** Exactly one poll turns an approval into a session; later polls see [ClaimResult.Expired]. */
    @Transactional
    fun claim(challengeId: UUID, pollSecret: String): ClaimResult {
        val challenge = challenges.findById(challengeId).orElse(null)
            ?.takeIf { WebTokens.matches(pollSecret, it.pollSecretHash) }
            ?: throw NotFoundException("Login request not found")
        val now = clock.instant()
        return when (challenge.status) {
            WebLoginStatus.PENDING -> if (now.isBefore(challenge.expiresAt)) ClaimResult.Pending else ClaimResult.Expired
            WebLoginStatus.APPROVED ->
                if (now.isBefore(challenge.expiresAt.plus(CLAIM_GRACE)) && challenges.markClaimed(challenge.id) == 1) {
                    ClaimResult.Approved(sessions.issue(checkNotNull(challenge.approvedBy), challenge.userAgent))
                } else ClaimResult.Expired
            WebLoginStatus.CLAIMED, WebLoginStatus.EXPIRED -> ClaimResult.Expired
        }
    }

    /** Pending codes past their lifetime free their code; rows older than a day are removed. */
    @Scheduled(cron = "15 * * * * *", zone = "Europe/Moscow")
    fun expireChallenges() {
        try {
            val now = clock.instant()
            challenges.expireBefore(now)
            challenges.deleteCreatedBefore(now.minus(RETENTION))
        } catch (error: Exception) {
            // Spring's default scheduled-task logger includes unsafe exception messages and causes.
            logger.error("Web login expiry failed: {}", SafeDiagnostics.describe(error), error)
        }
    }

    private fun freeCode(): String {
        repeat(CODE_ATTEMPTS) {
            val code = String(CharArray(CODE_LENGTH) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] })
            if (!challenges.existsByCodeAndStatus(code, WebLoginStatus.PENDING)) return code
        }
        throw BusinessRuleException("No free login code, try again")
    }

    companion object {
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 8
        val CODE_TTL: Duration = Duration.ofMinutes(2)
        /** A browser polling every few seconds may see an approval made in the code's last moment. */
        val CLAIM_GRACE: Duration = Duration.ofMinutes(1)
        val RATE_WINDOW: Duration = Duration.ofMinutes(10)
        const val MAX_UNAPPROVED_PER_WINDOW = 10
        val RETENTION: Duration = Duration.ofDays(1)
        private const val CODE_ATTEMPTS = 5
        private val CODE_PATTERN = Regex("^[$CODE_ALPHABET]{$CODE_LENGTH}$")
        private val logger = LoggerFactory.getLogger(WebLoginService::class.java)
    }
}
