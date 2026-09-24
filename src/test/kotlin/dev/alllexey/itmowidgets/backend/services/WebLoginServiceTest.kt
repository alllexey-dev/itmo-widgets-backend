package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.ClaimResult
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.exceptions.TooManyRequestsException
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.model.WebLoginStatus
import dev.alllexey.itmowidgets.backend.repositories.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.repositories.WebLoginChallengeRepository
import dev.alllexey.itmowidgets.backend.repositories.WebSessionRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

@Import(WebLoginService::class, WebSessionService::class, WebLoginServiceTest.TimeConfig::class)
class WebLoginServiceTest @Autowired constructor(
    private val service: WebLoginService,
    private val webSessions: WebSessionService,
    private val challenges: WebLoginChallengeRepository,
    private val sessions: WebSessionRepository,
    private val clock: MutableClock,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig { @Bean fun clock() = MutableClock() }

    private lateinit var approver: User

    @BeforeEach
    fun reset() {
        clock.now = NOW
        approver = em.persistAndFlush(User(isu = 954001, name = "Synthetic user", pictureUrl = null, createdAt = NOW).apply {
            settings = UserSettingsEntity(user = this)
        })
    }

    @Test
    fun `a created code previews approves and is claimed into exactly one session`() {
        val created = service.createChallenge("Synthetic browser", IP)
        assertEquals(8, created.code.length)
        assertTrue(created.code.all { it in WebLoginService.CODE_ALPHABET })
        assertEquals(NOW.plus(WebLoginService.CODE_TTL), created.expiresAt)
        val stored = challenges.findById(created.id).orElseThrow()
        assertNotEquals(created.pollSecret, stored.pollSecretHash)
        assertEquals(64, stored.pollSecretHash.length)
        assertEquals(IP, stored.clientIp)
        assertEquals(ClaimResult.Pending, service.claim(created.id, created.pollSecret))

        clock.now = NOW.plusSeconds(20)
        val preview = service.preview(approver.id, " ${created.code.lowercase()} ")
        assertEquals(created.id, preview.challengeId)
        assertEquals("Synthetic browser", preview.userAgent)
        assertEquals(NOW, preview.createdAt)
        service.approve(approver.id, created.id)
        service.approve(approver.id, created.id)
        assertFailsWith<NotFoundException> { service.preview(approver.id, created.code) }
        assertFailsWith<NotFoundException> { service.approve(UUID.randomUUID(), created.id) }

        val approved = assertIs<ClaimResult.Approved>(service.claim(created.id, created.pollSecret))
        assertEquals(approver.id, webSessions.resolve(approved.sessionToken))
        assertEquals(WebLoginStatus.CLAIMED, challenges.findById(created.id).orElseThrow().status)
        assertEquals(ClaimResult.Expired, service.claim(created.id, created.pollSecret))
        assertEquals(1, sessions.findActiveByUser(approver.id, clock.instant()).size)
        assertEquals("Synthetic browser", sessions.findAll().single().userAgent)
    }

    @Test
    fun `an expired code cannot be previewed approved or claimed and a foreign secret finds nothing`() {
        val created = service.createChallenge(null, IP)
        val other = service.createChallenge(null, IP)
        assertFailsWith<NotFoundException> { service.claim(created.id, other.pollSecret) }
        assertFailsWith<NotFoundException> { service.claim(UUID.randomUUID(), created.pollSecret) }
        assertFailsWith<NotFoundException> { service.preview(approver.id, "IO01IO01") }

        clock.now = created.expiresAt
        assertFailsWith<NotFoundException> { service.preview(approver.id, created.code) }
        assertFailsWith<NotFoundException> { service.approve(approver.id, created.id) }
        assertEquals(ClaimResult.Expired, service.claim(created.id, created.pollSecret))
        assertEquals(0, sessions.count())
    }

    @Test
    fun `an approval is claimable briefly after the code expires but not later`() {
        val late = service.createChallenge(null, IP)
        val stale = service.createChallenge(null, IP)
        clock.now = late.expiresAt.minusSeconds(1)
        service.approve(approver.id, late.id)
        service.approve(approver.id, stale.id)

        clock.now = late.expiresAt.plusSeconds(30)
        assertIs<ClaimResult.Approved>(service.claim(late.id, late.pollSecret))
        clock.now = stale.expiresAt.plus(WebLoginService.CLAIM_GRACE)
        assertEquals(ClaimResult.Expired, service.claim(stale.id, stale.pollSecret))
        assertEquals(1, sessions.count())
    }

    @Test
    fun `an address may hold ten unapproved codes per ten minutes`() {
        repeat(WebLoginService.MAX_UNAPPROVED_PER_WINDOW) { service.createChallenge(null, IP) }
        val error = assertFailsWith<TooManyRequestsException> { service.createChallenge(null, IP) }
        assertEquals("Too many login codes, try again later", error.message)
        service.createChallenge(null, "198.51.100.1")

        // Expiry does not reset the window; only its end does.
        clock.now = NOW.plus(Duration.ofMinutes(5))
        service.expireChallenges()
        assertFailsWith<TooManyRequestsException> { service.createChallenge(null, IP) }
        clock.now = NOW.plus(WebLoginService.RATE_WINDOW).plusSeconds(1)
        service.createChallenge(null, IP)
    }

    @Test
    fun `the scheduler expires overdue pending codes and removes day-old rows`() {
        val overdue = service.createChallenge(null, IP)
        clock.now = NOW.plusSeconds(60)
        val fresh = service.createChallenge(null, IP)
        clock.now = overdue.expiresAt
        service.expireChallenges()
        em.clear()

        assertEquals(WebLoginStatus.EXPIRED, challenges.findById(overdue.id).orElseThrow().status)
        assertEquals(WebLoginStatus.PENDING, challenges.findById(fresh.id).orElseThrow().status)
        clock.now = NOW.plus(Duration.ofDays(1)).plusSeconds(1)
        service.expireChallenges()
        assertFalse(challenges.existsById(overdue.id))
        assertEquals(WebLoginStatus.EXPIRED, challenges.findById(fresh.id).orElseThrow().status)
    }

    class MutableClock : Clock() {
        @Volatile var now: Instant = NOW
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
        const val IP = "203.0.113.7"
    }
}
