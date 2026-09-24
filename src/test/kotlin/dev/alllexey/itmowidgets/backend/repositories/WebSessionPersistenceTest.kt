package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.data.domain.PageRequest

class WebSessionPersistenceTest @Autowired constructor(
    private val em: TestEntityManager,
    private val challenges: WebLoginChallengeRepository,
    private val sessions: WebSessionRepository,
    private val settings: AppSettingRepository,
    private val audit: AdminAuditRepository,
) : PostgreSqlRepositoryTest() {
    private val now = Instant.parse("2026-09-24T09:00:00Z")

    @Test
    fun `a pending code is found only while pending and unexpired`() {
        val approver = user(953001)
        val pending = challenge("ABCD2345")
        challenge("EXPD2345", expiresAt = now.minusSeconds(1))
        challenge("USED2345", status = WebLoginStatus.CLAIMED, approvedBy = approver.id)
        challenge("APPR2345", status = WebLoginStatus.APPROVED, approvedBy = approver.id)
        challenge("GONE2345", status = WebLoginStatus.EXPIRED)
        em.flush(); em.clear()

        val found = assertNotNull(challenges.findPendingByCode("ABCD2345", now))
        assertEquals(pending.id, found.id)
        assertEquals("a".repeat(64), found.pollSecretHash)
        assertEquals(WebLoginStatus.PENDING, found.status)
        for (code in listOf("EXPD2345", "USED2345", "APPR2345", "GONE2345", "NONE2345")) {
            assertNull(challenges.findPendingByCode(code, now), code)
        }
        assertNull(challenges.findPendingByCode("ABCD2345", pending.expiresAt))
        assertTrue(challenges.existsByCodeAndStatus("EXPD2345", WebLoginStatus.PENDING))
        assertFalse(challenges.existsByCodeAndStatus("USED2345", WebLoginStatus.PENDING))
    }

    @Test
    fun `an address counts its unapproved challenges within the window only`() {
        val approver = user(953011)
        challenge("AAAA2345", createdAt = now.minusSeconds(60))
        challenge("BBBB2345", createdAt = now.minusSeconds(300), status = WebLoginStatus.EXPIRED)
        challenge("CCCC2345", createdAt = now.minusSeconds(120), status = WebLoginStatus.CLAIMED, approvedBy = approver.id)
        challenge("DDDD2345", createdAt = now.minusSeconds(700))
        challenge("EEEE2345", createdAt = now.minusSeconds(60), clientIp = "198.51.100.1")
        em.flush(); em.clear()

        assertEquals(2, challenges.countByClientIpSince(IP, now.minusSeconds(600)))
        assertEquals(3, challenges.countByClientIpSince(IP, now.minusSeconds(900)))
        assertEquals(1, challenges.countByClientIpSince("198.51.100.1", now.minusSeconds(600)))
        assertEquals(0, challenges.countByClientIpSince("192.0.2.1", now.minusSeconds(600)))
    }

    @Test
    fun `approve claim and expiry are conditional transitions and old rows are deleted`() {
        val approver = user(953021)
        val open = challenge("AAAA2345")
        val late = challenge("BBBB2345", expiresAt = now.minusSeconds(1))
        val old = challenge("CCCC2345", createdAt = now.minusSeconds(90_000), expiresAt = now.minusSeconds(89_000))
        em.flush(); em.clear()

        assertEquals(0, challenges.approve(late.id, approver.id, now))
        assertEquals(1, challenges.approve(open.id, approver.id, now))
        assertEquals(0, challenges.approve(open.id, approver.id, now))
        val approved = challenges.findById(open.id).orElseThrow()
        assertEquals(WebLoginStatus.APPROVED, approved.status)
        assertEquals(approver.id, approved.approvedBy)
        assertEquals(now, approved.approvedAt)
        assertEquals(0, challenges.markClaimed(late.id))
        assertEquals(1, challenges.markClaimed(open.id))
        assertEquals(0, challenges.markClaimed(open.id))
        assertEquals(WebLoginStatus.CLAIMED, challenges.findById(open.id).orElseThrow().status)

        assertEquals(2, challenges.expireBefore(now))
        assertEquals(WebLoginStatus.EXPIRED, challenges.findById(late.id).orElseThrow().status)
        assertEquals(WebLoginStatus.CLAIMED, challenges.findById(open.id).orElseThrow().status)
        assertEquals(1, challenges.deleteCreatedBefore(now.minusSeconds(86_400)))
        assertFalse(challenges.existsById(old.id))
    }

    @Test
    fun `an active session is neither revoked nor past its lifetime and expired rows are deleted`() {
        val owner = user(953031)
        val other = user(953032)
        val active = session(owner, "1", lastSeenAt = now.minusSeconds(60))
        session(owner, "2", revokedAt = now.minusSeconds(10))
        session(owner, "3", expiresAt = now)
        val older = session(owner, "4", lastSeenAt = now.minusSeconds(600))
        session(other, "5")
        em.flush(); em.clear()

        assertEquals(active.id, sessions.findActiveByTokenHash(hash("1"), now)?.id)
        for (token in listOf("2", "3", "6")) assertNull(sessions.findActiveByTokenHash(hash(token), now), token)
        assertEquals(listOf(active.id, older.id), sessions.findActiveByUser(owner.id, now).map { it.id })
        assertEquals(1, sessions.revokeByTokenHash(hash("1"), now))
        assertEquals(0, sessions.revokeByTokenHash(hash("1"), now))
        assertNull(sessions.findActiveByTokenHash(hash("1"), now))
        assertEquals(1, sessions.deleteExpiredBefore(now.plusSeconds(1)))
        assertEquals(4, sessions.count())
    }

    @Test
    fun `the audit page is newest first and settings round trip`() {
        val admin = user(953041)
        val first = em.persist(AdminAuditEntity(actorId = admin.id, action = "ROLE_GRANTED", target = "953042", details = null, createdAt = now.minusSeconds(120)))
        val second = em.persist(AdminAuditEntity(actorId = admin.id, action = "APP_VERSION", target = "app", details = "2.2", createdAt = now.minusSeconds(60)))
        val third = em.persist(AdminAuditEntity(actorId = admin.id, action = "ROLE_REVOKED", target = "953042", details = null, createdAt = now))
        settings.save(AppSettingEntity("app.latest", "2.2", now, admin.id))
        em.flush(); em.clear()

        val page = audit.findPage(PageRequest.of(0, 2))
        assertEquals(3, page.totalElements)
        assertEquals(listOf(third.id, second.id), page.content.map { it.id })
        assertEquals(listOf(first.id), audit.findPage(PageRequest.of(1, 2)).content.map { it.id })
        assertEquals("2.2", settings.findById("app.latest").orElseThrow().value)
        assertEquals(admin.id, settings.findById("app.latest").orElseThrow().updatedBy)
    }

    private fun user(isu: Int) = em.persist(User(isu = isu, name = "Synthetic user", pictureUrl = null, createdAt = now).apply {
        settings = UserSettingsEntity(user = this)
    })

    private fun challenge(
        code: String,
        createdAt: Instant = now.minusSeconds(30),
        expiresAt: Instant = createdAt.plusSeconds(120),
        status: WebLoginStatus = WebLoginStatus.PENDING,
        approvedBy: UUID? = null,
        clientIp: String = IP,
    ) = em.persist(WebLoginChallengeEntity(code = code, pollSecretHash = "a".repeat(64), status = status,
        userAgent = "Synthetic browser", clientIp = clientIp, createdAt = createdAt, expiresAt = expiresAt,
        approvedBy = approvedBy, approvedAt = approvedBy?.let { createdAt }))

    private fun session(
        owner: User,
        token: String,
        lastSeenAt: Instant = now.minusSeconds(30),
        expiresAt: Instant = now.plusSeconds(3_600),
        revokedAt: Instant? = null,
    ) = em.persist(WebSessionEntity(userId = owner.id, tokenHash = hash(token), userAgent = null,
        createdAt = now.minusSeconds(3_600), lastSeenAt = lastSeenAt, expiresAt = expiresAt, revokedAt = revokedAt))

    private fun hash(token: String) = token.repeat(64)

    private companion object {
        const val IP = "203.0.113.7"
    }
}
