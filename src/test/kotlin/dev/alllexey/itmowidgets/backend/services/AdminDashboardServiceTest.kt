package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.AdminDashboard
import dev.alllexey.itmowidgets.backend.dto.SubjectLinkStatus
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.model.*
import dev.alllexey.itmowidgets.backend.repositories.PostgreSqlRepositoryTest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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

/** A far-future clock keeps the daily series free of rows other test classes commit; totals are compared as deltas. */
@Import(AdminDashboardService::class, AdminAccess::class, AdminDashboardServiceTest.TimeConfig::class)
class AdminDashboardServiceTest @Autowired constructor(
    private val service: AdminDashboardService,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig { @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC) }

    private var nextIsu = 960100
    private lateinit var admin: User

    @BeforeEach
    fun admin() {
        admin = user(NOW.minus(Duration.ofDays(400)))
        em.persistAndFlush(UserRoleEntity(UserRoleId(admin.id, UserRole.ADMIN), NOW))
    }

    @Test
    fun `thirty Moscow days are zero filled and totals count known data`() {
        val before = service.dashboard(admin.id)

        // 13:00 in Moscow; Moscow midnight is 21:00 UTC the day before.
        val today = user(Instant.parse("2031-03-14T21:30:00Z"))
        val yesterday = user(Instant.parse("2031-03-14T20:30:00Z"))
        user(Instant.parse("2031-02-13T21:00:00Z"))
        user(Instant.parse("2031-02-13T20:59:00Z"))
        device(today, NOW.minus(Duration.ofDays(1)))
        device(today, NOW.minus(Duration.ofDays(1)).plusSeconds(60))
        device(yesterday, NOW.minus(Duration.ofDays(10)))
        device(yesterday, NOW.minus(Duration.ofDays(40)))
        session(today, NOW.minus(Duration.ofDays(2)))
        session(today, NOW.minus(Duration.ofDays(8)))
        em.persist(FriendshipEntity(requester = today, addressee = yesterday, status = FriendshipEntity.Status.ACCEPTED,
            createdAt = NOW.minusSeconds(100), respondedAt = NOW.minusSeconds(50)))
        em.persist(FriendshipEntity(requester = admin, addressee = today, createdAt = NOW.minusSeconds(100)))
        link(today, LinkVisibility.PRIVATE, null, createdAt = NOW)
        link(today, LinkVisibility.ALL, LinkRevisionStatus.PENDING, createdAt = NOW)
        link(today, LinkVisibility.ALL, LinkRevisionStatus.APPROVED, createdAt = NOW.minus(Duration.ofDays(3)))
        link(today, LinkVisibility.ALL, LinkRevisionStatus.REJECTED, createdAt = NOW.minus(Duration.ofDays(3)))
        link(today, LinkVisibility.ALL, LinkRevisionStatus.APPROVED, hidden = true, createdAt = NOW.minus(Duration.ofDays(50)))
        em.persist(ModerationCaseEntity(targetType = ModerationTargetType.SUBJECT_RESOURCE, targetId = UUID.randomUUID(),
            reason = ModerationCaseReason.SUBMISSION, openedAt = NOW))
        em.flush(); em.clear()

        val after = service.dashboard(admin.id)
        assertEquals(4, after.totals.users - before.totals.users)
        assertEquals(2, after.totals.newUsers7d - before.totals.newUsers7d)
        assertEquals(2, after.totals.activeDevices7d - before.totals.activeDevices7d)
        assertEquals(3, after.totals.activeDevices30d - before.totals.activeDevices30d)
        assertEquals(1, after.totals.webSessions7d - before.totals.webSessions7d)
        assertEquals(1, after.totals.friendships - before.totals.friendships)
        assertEquals(1, after.totals.openCases - before.totals.openCases)
        assertEquals(SubjectLinkStatus.entries.toSet(), after.totals.links.keys)
        for (status in SubjectLinkStatus.entries) assertEquals(1, after.totals.links.getValue(status) - before.totals.links.getValue(status), status.name)
        assertEquals(before.totals.activeAutoSignEntries, after.totals.activeAutoSignEntries)
        assertEquals(before.totals.activeFreeSignEntries, after.totals.activeFreeSignEntries)

        assertEquals(30, after.days.size)
        assertEquals(LocalDate.parse("2031-02-14"), after.days.first().date)
        assertEquals(LocalDate.parse("2031-03-15"), after.days.last().date)
        assertEquals((0L until 30L).map { LocalDate.parse("2031-02-14").plusDays(it) }, after.days.map { it.date })
        val byDate = after.days.associateBy { it.date.toString() }
        assertEquals(1, byDate.getValue("2031-03-15").newUsers)
        assertEquals(1, byDate.getValue("2031-03-14").newUsers)
        assertEquals(1, byDate.getValue("2031-02-14").newUsers)
        assertEquals(2, byDate.getValue("2031-03-14").activeDevices)
        assertEquals(1, byDate.getValue("2031-03-05").activeDevices)
        assertEquals(2, byDate.getValue("2031-03-15").createdLinks)
        assertEquals(2, byDate.getValue("2031-03-12").createdLinks)
        assertEquals(3, after.days.sumOf { it.newUsers })
        assertEquals(3, after.days.sumOf { it.activeDevices })
        assertEquals(4, after.days.sumOf { it.createdLinks })
        assertTrue(before.days.all { it.newUsers == 0L && it.activeDevices == 0L && it.createdLinks == 0L })
    }

    @Test
    fun `only admins read the dashboard`() {
        val moderator = user(NOW)
        em.persistAndFlush(UserRoleEntity(UserRoleId(moderator.id, UserRole.MODERATOR), NOW))
        assertFailsWith<PermissionDeniedException> { service.dashboard(moderator.id) }
        assertIs<AdminDashboard>(service.dashboard(admin.id))
    }

    private fun user(createdAt: Instant): User = em.persist(User(isu = nextIsu++, name = "Synthetic user", pictureUrl = null, createdAt = createdAt).apply {
        settings = UserSettingsEntity(user = this)
    })

    private fun device(owner: User, lastLogin: Instant) =
        em.persist(Device(user = owner, fcmToken = "synthetic-${UUID.randomUUID()}", deviceName = "Synthetic", lastLogin = lastLogin))

    private fun session(owner: User, createdAt: Instant) = em.persist(WebSessionEntity(userId = owner.id,
        tokenHash = UUID.randomUUID().toString().replace("-", "").repeat(2), userAgent = null, createdAt = createdAt,
        lastSeenAt = createdAt, expiresAt = createdAt.plusSeconds(3600)))

    private fun link(owner: User, visibility: LinkVisibility, status: LinkRevisionStatus?, hidden: Boolean = false, createdAt: Instant) {
        val url = "https://example.org/${UUID.randomUUID()}"
        val link = em.persist(SubjectLinkEntity(id = UUID.randomUUID(), owner = owner, subjectId = 42, subjectName = "Предмет",
            periodKey = "2031-1", category = LinkCategory.MATERIALS, url = url, normalizedUrl = url, title = null,
            visibility = visibility, hiddenAt = if (hidden) createdAt else null, createdAt = createdAt, updatedAt = createdAt))
        if (status != null) {
            if (status != LinkRevisionStatus.APPROVED) revision(link, 1, LinkRevisionStatus.APPROVED, createdAt)
            revision(link, if (status == LinkRevisionStatus.APPROVED) 1 else 2, status, createdAt)
        }
    }

    private fun revision(link: SubjectLinkEntity, number: Int, status: LinkRevisionStatus, at: Instant) = em.persist(SubjectLinkRevisionEntity(
        link = link, number = number, category = link.category, url = link.url, normalizedUrl = link.normalizedUrl, title = null,
        visibility = link.visibility, status = status, submittedAt = at, decidedAt = if (status == LinkRevisionStatus.PENDING) null else at))

    private companion object {
        val NOW: Instant = Instant.parse("2031-03-15T10:00:00Z")
    }
}
