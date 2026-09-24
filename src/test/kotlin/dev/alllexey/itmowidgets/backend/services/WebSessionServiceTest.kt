package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.repositories.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.repositories.WebSessionRepository
import java.time.Duration
import java.time.Instant
import kotlin.test.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.context.annotation.Import

@Import(WebSessionService::class, WebLoginServiceTest.TimeConfig::class)
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
        user = em.persistAndFlush(User(isu = 955001, name = "Synthetic user", pictureUrl = null, createdAt = NOW).apply {
            settings = UserSettingsEntity(user = this)
        })
    }

    @Test
    fun `a session stores only the token hash and use extends its idle window`() {
        val token = service.issue(user.id, "Synthetic browser")
        val stored = sessions.findAll().single()
        assertNotEquals(token, stored.tokenHash)
        assertEquals(64, stored.tokenHash.length)
        assertEquals(NOW.plus(WebSessionService.MAX_LIFETIME), stored.expiresAt)

        clock.now = NOW.plus(Duration.ofMinutes(119))
        assertEquals(user.id, service.resolve(token))
        clock.now = clock.now.plus(Duration.ofMinutes(119))
        assertEquals(user.id, service.resolve(token))
        em.flush(); em.clear()
        assertEquals(clock.now, sessions.findById(stored.id).orElseThrow().lastSeenAt)
    }

    @Test
    fun `two idle hours end a session`() {
        val token = service.issue(user.id, null)
        clock.now = NOW.plus(WebSessionService.IDLE_TIMEOUT)
        assertNull(service.resolve(token))
        clock.now = NOW.plus(WebSessionService.IDLE_TIMEOUT).minusSeconds(1)
        assertEquals(user.id, service.resolve(token))
    }

    @Test
    fun `twelve hours end a session however active it is`() {
        val token = service.issue(user.id, null)
        var now = NOW
        while (now.isBefore(NOW.plus(WebSessionService.MAX_LIFETIME).minus(Duration.ofHours(1)))) {
            now = now.plus(Duration.ofHours(1)); clock.now = now
            assertEquals(user.id, service.resolve(token), now.toString())
        }
        clock.now = NOW.plus(WebSessionService.MAX_LIFETIME)
        assertNull(service.resolve(token))
    }

    @Test
    fun `a revoked session and malformed or unknown tokens resolve to nobody`() {
        val token = service.issue(user.id, null)
        val other = service.issue(user.id, null)
        service.revoke(token)
        service.revoke("not a token")
        assertNull(service.resolve(token))
        assertEquals(user.id, service.resolve(other))
        for (invalid in listOf("", "short", other + "x", WebTokens.random())) assertNull(service.resolve(invalid))
    }

    @Test
    fun `retention deletes sessions ended more than thirty days ago`() {
        service.issue(user.id, null)
        clock.now = NOW.plus(Duration.ofDays(20))
        service.issue(user.id, null)
        clock.now = NOW.plus(WebSessionService.MAX_LIFETIME).plus(WebSessionService.RETENTION).plusSeconds(1)
        assertEquals(1, service.deleteExpired())
        assertEquals(1, sessions.count())
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
    }
}
