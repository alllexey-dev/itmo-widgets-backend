package dev.alllexey.itmowidgets.backend.feature.weblogin.service

import dev.alllexey.itmowidgets.backend.feature.users.model.User
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
    fun `a session stores only the token hash and use slides its idle window`() {
        val token = service.issue(user.id, "Synthetic browser")
        val stored = sessions.findAll().single()
        assertNotEquals(token, stored.tokenHash)
        assertEquals(64, stored.tokenHash.length)
        assertEquals(NOW.plus(MAX_LIFETIME), stored.expiresAt)

        clock.now = NOW.plus(IDLE_TIMEOUT).minusSeconds(1)
        assertEquals(user.id, service.resolve(token)?.userId)
        clock.now = clock.now.plus(IDLE_TIMEOUT).minusSeconds(1)
        assertEquals(user.id, service.resolve(token)?.userId)
        assertEquals(clock.now, lastSeenAt(stored.id))
    }

    @Test
    fun `fourteen idle days end a session`() {
        val token = service.issue(user.id, null)
        clock.now = NOW.plus(IDLE_TIMEOUT)
        assertNull(service.resolve(token))
        clock.now = NOW.plus(IDLE_TIMEOUT).minusSeconds(1)
        assertEquals(user.id, service.resolve(token)?.userId)
    }

    @Test
    fun `sixty days end a session however active it is`() {
        val token = service.issue(user.id, null)
        var now = NOW
        while (now.isBefore(NOW.plus(MAX_LIFETIME).minus(Duration.ofDays(1)))) {
            now = now.plus(Duration.ofDays(1))
            clock.now = now
            assertEquals(user.id, service.resolve(token)?.userId, now.toString())
        }
        clock.now = NOW.plus(MAX_LIFETIME)
        assertNull(service.resolve(token))
    }

    @Test
    fun `a session is fresh for admin routes up to twelve hours after sign-in however active it is`() {
        val token = service.issue(user.id, null)
        clock.now = NOW.plus(Duration.ofHours(6))
        assertEquals(ActiveWebSession(user.id, signedInAt = NOW, freshForAdmin = true), service.resolve(token))
        clock.now = NOW.plus(ADMIN_MAX_AGE)
        assertEquals(ActiveWebSession(user.id, signedInAt = NOW, freshForAdmin = true), service.resolve(token))
        clock.now = NOW.plus(ADMIN_MAX_AGE).plusSeconds(1)
        assertEquals(ActiveWebSession(user.id, signedInAt = NOW, freshForAdmin = false), service.resolve(token))
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
    fun `a revoked session and malformed or unknown tokens resolve to nobody`() {
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

    private fun lastSeenAt(id: UUID): Instant {
        em.flush()
        em.clear()
        return sessions.findById(id).orElseThrow().lastSeenAt
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")

        // The production defaults of WebSessionConfig, bound from application.properties.
        val IDLE_TIMEOUT: Duration = Duration.ofDays(14)
        val MAX_LIFETIME: Duration = Duration.ofDays(60)
        val ADMIN_MAX_AGE: Duration = Duration.ofHours(12)
    }
}
