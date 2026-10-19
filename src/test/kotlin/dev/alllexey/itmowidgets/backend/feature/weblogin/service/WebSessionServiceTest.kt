package dev.alllexey.itmowidgets.backend.feature.weblogin.service

import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.weblogin.model.WebSessionEntity
import dev.alllexey.itmowidgets.backend.feature.weblogin.persistence.WebSessionRepository
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.testing.persistUser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.*

// The same beans as WebLoginServiceTest, so both classes share one Spring context.
@Import(WebLoginService::class, WebSessionService::class, WebLoginServiceTest.TimeConfig::class)
class WebSessionServiceTest @Autowired constructor(
    private val service: WebSessionService,
    private val sessions: WebSessionRepository,
    private val clock: WebLoginServiceTest.MutableClock,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    private lateinit var user: User

    @BeforeEach
    fun reset() {
        clock.now = NOW
        user = em.persistUser(955001, createdAt = NOW)
    }

    @Test
    fun `a session stores only token hashes and its access token lives one hour`() {
        val issued = service.issueSession(user.id, "Synthetic browser")
        val stored = sessions.findAll().single()
        assertEquals(WebTokens.sha256(issued.token), stored.tokenHash)
        assertEquals(WebTokens.sha256(issued.refreshToken), stored.refreshHash)
        assertEquals(NOW.plus(MAX_LIFETIME), stored.expiresAt)
        assertEquals(NOW.plus(ACCESS_TTL), stored.accessExpiresAt)

        clock.now = NOW.plus(ACCESS_TTL).minusSeconds(1)
        assertEquals(user.id, service.resolve(issued.token)?.userId)
        clock.now = NOW.plus(ACCESS_TTL)
        assertNull(service.resolve(issued.token))
    }

    @Test
    fun `a refresh rotates both tokens within the same session and slides its idle window`() {
        val issued = service.issueSession(user.id, null)
        clock.now = NOW.plus(Duration.ofHours(2))
        val next = rotated(issued)

        assertEquals(issued.id, next.id)
        assertNotEquals(issued.token, next.token)
        assertNotEquals(issued.refreshToken, next.refreshToken)
        assertNull(service.resolve(issued.token))
        assertEquals(user.id, service.resolve(next.token)?.userId)
        assertEquals(clock.now, lastSeenAt(issued.id))
        assertEquals(1, sessions.count())
    }

    @Test
    fun `thirty idle days end a session`() {
        val kept = service.issueSession(user.id, null)
        val idle = service.issueSession(user.id, null)
        clock.now = NOW.plus(IDLE_TIMEOUT).minusSeconds(1)
        rotated(kept)
        clock.now = NOW.plus(IDLE_TIMEOUT)
        assertEquals(WebSessionRefresh.Rejected, service.refresh(idle.refreshToken))
    }

    @Test
    fun `a hundred and eighty days end a session however often it refreshes`() {
        var current = service.issueSession(user.id, null)
        var now = NOW
        while (now.isBefore(NOW.plus(MAX_LIFETIME).minus(Duration.ofDays(1)))) {
            now = now.plus(Duration.ofDays(1))
            clock.now = now
            current = rotated(current)
            assertEquals(user.id, service.resolve(current.token)?.userId, now.toString())
        }
        clock.now = NOW.plus(MAX_LIFETIME)
        assertNull(service.resolve(current.token))
        assertEquals(WebSessionRefresh.Rejected, service.refresh(current.refreshToken))
    }

    @Test
    fun `the previous refresh token is a no-op within the grace and a replay after it revokes the session`() {
        val issued = service.issueSession(user.id, null)
        val next = rotated(issued)
        clock.now = NOW.plus(WebSessionService.ROTATION_GRACE).minusSeconds(1)
        assertEquals(WebSessionRefresh.AlreadyRotated, service.refresh(issued.refreshToken))
        assertEquals(user.id, service.resolve(next.token)?.userId)

        clock.now = NOW.plus(WebSessionService.ROTATION_GRACE)
        assertEquals(WebSessionRefresh.Rejected, service.refresh(issued.refreshToken))
        assertNull(service.resolve(next.token))
        assertEquals(WebSessionRefresh.Rejected, service.refresh(next.refreshToken))
    }

    @Test
    fun `a revoked session and malformed or unknown refresh tokens do not refresh`() {
        val issued = service.issueSession(user.id, null)
        val other = service.issueSession(user.id, null)
        service.revoke(issued.token)
        assertEquals(WebSessionRefresh.Rejected, service.refresh(issued.refreshToken))
        for (invalid in listOf("", "short", other.refreshToken + "x", WebTokens.random(), other.token)) {
            assertEquals(WebSessionRefresh.Rejected, service.refresh(invalid))
        }
        rotated(other)
    }

    @Test
    fun `a session issued before refresh tokens keeps its cookie until its own limits`() {
        val token = WebTokens.random()
        em.persistAndFlush(
            WebSessionEntity(
                userId = user.id,
                tokenHash = WebTokens.sha256(token),
                userAgent = null,
                createdAt = NOW,
                lastSeenAt = NOW,
                expiresAt = NOW.plus(Duration.ofDays(60)),
            ),
        )
        clock.now = NOW.plus(Duration.ofDays(1))
        assertEquals(user.id, service.resolve(token)?.userId)
        clock.now = NOW.plus(Duration.ofDays(60))
        assertNull(service.resolve(token))
    }

    @Test
    fun `a session is fresh for admin routes up to twelve hours after sign-in however often it refreshes`() {
        var current = service.issueSession(user.id, null)
        fun at(offset: Duration): ActiveWebSession? {
            clock.now = NOW.plus(offset)
            current = rotated(current)
            return service.resolve(current.token)
        }
        val id = current.id
        assertEquals(ActiveWebSession(user.id, signedInAt = NOW, freshForAdmin = true, sessionId = id), at(Duration.ofHours(6)))
        assertEquals(ActiveWebSession(user.id, signedInAt = NOW, freshForAdmin = true, sessionId = id), at(ADMIN_MAX_AGE))
        assertEquals(
            ActiveWebSession(user.id, signedInAt = NOW, freshForAdmin = false, sessionId = id),
            at(ADMIN_MAX_AGE.plusSeconds(1)),
        )
    }

    @Test
    fun `use writes last seen at most once per five minutes`() {
        val token = service.issue(user.id, null)
        val id = sessions.findAll().single().id
        clock.now = NOW.plus(WebSessionService.LAST_SEEN_STEP).minusSeconds(1)
        assertEquals(user.id, service.resolve(token)?.userId)
        assertEquals(NOW, lastSeenAt(id))

        clock.now = NOW.plus(WebSessionService.LAST_SEEN_STEP)
        assertEquals(user.id, service.resolve(token)?.userId)
        assertEquals(clock.now, lastSeenAt(id))
    }

    @Test
    fun `a revoked session and malformed or unknown access tokens resolve to nobody`() {
        val token = service.issue(user.id, null)
        val other = service.issue(user.id, null)
        service.revoke(token)
        service.revoke("not a token")
        assertNull(service.resolve(token))
        assertEquals(user.id, service.resolve(other)?.userId)
        for (invalid in listOf("", "short", other + "x", WebTokens.random())) assertNull(service.resolve(invalid))
    }

    @Test
    fun `retention deletes sessions ended more than ninety days ago`() {
        service.issue(user.id, null)
        clock.now = NOW.plus(Duration.ofDays(20))
        service.issue(user.id, null)
        clock.now = NOW.plus(MAX_LIFETIME).plus(Duration.ofDays(90)).plusSeconds(1)
        assertEquals(1, service.deleteExpired())
        assertEquals(1, sessions.count())
    }

    private fun rotated(session: IssuedWebSession): IssuedWebSession =
        assertIs<WebSessionRefresh.Rotated>(service.refresh(session.refreshToken)).session

    private fun lastSeenAt(id: UUID): Instant {
        em.flush()
        em.clear()
        return sessions.findById(id).orElseThrow().lastSeenAt
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")

        // The production defaults of WebSessionConfig, bound from application.properties.
        val IDLE_TIMEOUT: Duration = Duration.ofDays(30)
        val MAX_LIFETIME: Duration = Duration.ofDays(180)
        val ADMIN_MAX_AGE: Duration = Duration.ofHours(12)
        val ACCESS_TTL: Duration = Duration.ofHours(1)
    }
}
