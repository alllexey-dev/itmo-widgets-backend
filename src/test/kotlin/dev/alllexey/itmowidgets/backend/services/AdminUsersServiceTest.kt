package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.UserData
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.model.*
import dev.alllexey.itmowidgets.backend.repositories.AdminAuditRepository
import dev.alllexey.itmowidgets.backend.repositories.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.core.model.GroupData
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.bean.override.mockito.MockitoBean

@Import(AdminUsersService::class, AdminAccess::class, AdminUserSummaries::class, AdminRestrictionViews::class,
    AdminAuditService::class, AdminUsersServiceTest.TimeConfig::class)
class AdminUsersServiceTest @Autowired constructor(
    private val service: AdminUsersService,
    private val audit: AdminAuditRepository,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var currentGroups: CurrentStudyGroupsService

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig { @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC) }

    private lateinit var admin: User
    private lateinit var faculty: FacultyEntity
    private lateinit var qualification: QualificationEntity

    @BeforeEach
    fun fixture() {
        faculty = em.persist(FacultyEntity(958_001, "Synthetic faculty", "СИН"))
        qualification = em.persist(QualificationEntity(958_001, "Synthetic"))
        admin = user(958100, "Администратор Синтетический")
        em.persistAndFlush(UserRoleEntity(UserRoleId(admin.id, UserRole.ADMIN), NOW))
        doAnswer { invocation ->
            val data = invocation.getArgument<UserData>(0)
            data.copy(groups = listOf(GroupData("P9999", 4, "ФПИиКТ")))
        }.`when`(currentGroups).userData(any(UserData::class.java) ?: SAMPLE)
    }

    @Test
    fun `search matches an ISU prefix a name part in any case and a stored group newest first`() {
        val older = user(958201, "Иванова Анна", createdAt = NOW.minusSeconds(3600), groups = listOf(group("P9101", 1), group("P9201", 2)))
        val newer = user(958202, "Петров Пётр", createdAt = NOW.minusSeconds(60), groups = listOf(group("Z9301", 3)))
        user(958303, "Сидоров_Иван")
        em.persistAndFlush(UserRoleEntity(UserRoleId(newer.id, UserRole.MODERATOR), NOW))
        em.flush(); em.clear()

        val byIsu = service.search(admin.id, "95820", 0, 20)
        assertEquals(listOf(958202, 958201), byIsu.items.map { it.isu })
        assertEquals(2, byIsu.total)
        assertEquals(listOf("MODERATOR"), byIsu.items[0].roles)
        assertEquals(emptyList(), byIsu.items[1].roles)
        assertEquals(listOf("P9201", "P9101"), byIsu.items[1].groups.map { it.name })
        assertEquals(GroupData("P9201", 2, "СИН"), byIsu.items[1].groups[0])
        assertEquals(older.createdAt, byIsu.items[1].createdAt)

        assertEquals(listOf(958201), service.search(admin.id, "  ИВАНОВА ", 0, 20).items.map { it.isu })
        assertEquals(listOf(958202), service.search(admin.id, "z93", 0, 20).items.map { it.isu })
        // LIKE wildcards in the query are literal characters.
        assertEquals(listOf(958303), service.search(admin.id, "в_и", 0, 20).items.map { it.isu })
        assertTrue(service.search(admin.id, "%", 0, 20).items.isEmpty())

        val paged = service.search(admin.id, "9582", 1, 1)
        assertEquals(listOf(958201), paged.items.map { it.isu })
        assertEquals(1, paged.page); assertEquals(1, paged.size); assertEquals(2, paged.total)
        assertTrue(service.search(admin.id, null, 0, 100).items.map { it.isu }.containsAll(listOf(958100, 958201, 958202, 958303)))
        assertFailsWith<InvalidRequestDataException> { service.search(admin.id, null, 0, 101) }
        assertFailsWith<InvalidRequestDataException> { service.search(admin.id, null, -1, 20) }
    }

    @Test
    fun `detail joins roles devices friends links restrictions and the latest activity`() {
        val student = user(958401, "Синтетический Студент", groups = listOf(group("P9101", 1), group("P9201", 2)))
        val friend = user(958402, "Друг")
        val stranger = user(958403, "Незнакомец")
        em.persist(UserRoleEntity(UserRoleId(student.id, UserRole.MODERATOR), NOW))
        em.persist(FriendshipEntity(requester = student, addressee = friend, status = FriendshipEntity.Status.ACCEPTED,
            createdAt = NOW.minusSeconds(100), respondedAt = NOW.minusSeconds(50)))
        em.persist(FriendshipEntity(requester = stranger, addressee = student, createdAt = NOW.minusSeconds(100)))
        em.persist(Device(user = student, fcmToken = "synthetic-token-1", deviceName = "Pixel", lastLogin = NOW.minusSeconds(7200)))
        em.persist(Device(user = student, fcmToken = "synthetic-token-2", deviceName = "Tablet", lastLogin = NOW.minusSeconds(86_400)))
        em.persist(WebSessionEntity(userId = student.id, tokenHash = "b".repeat(64), userAgent = null, createdAt = NOW.minusSeconds(600),
            lastSeenAt = NOW.minusSeconds(300), expiresAt = NOW.plusSeconds(3600)))
        link(student); link(student)
        val case = em.persist(ModerationCaseEntity(targetType = ModerationTargetType.SUBJECT_RESOURCE, targetId = UUID.randomUUID(),
            reason = ModerationCaseReason.REPORTS, openedAt = NOW.minusSeconds(900)))
        val decision = em.persist(ModerationDecisionEntity(case = case, moderator = admin, action = ModerationAction.RESTRICT_USER,
            restrictionCapability = RestrictionCapability.VOTE, restrictionDays = 7, createdAt = NOW.minusSeconds(800)))
        em.persist(UserRestrictionEntity(user = student, capability = RestrictionCapability.VOTE, decision = decision, reason = "Спам",
            startsAt = NOW.minusSeconds(800), expiresAt = NOW.plusSeconds(86_400)))
        em.persist(UserRestrictionEntity(user = student, capability = RestrictionCapability.REPORT, decision = decision, reason = "Старое",
            startsAt = NOW.minusSeconds(86_400 * 3L), revokedAt = NOW.minusSeconds(86_400), revokedBy = admin))
        em.flush(); em.clear()

        val detail = service.detail(admin.id, 958401)
        assertEquals(958401, detail.user.isu)
        assertEquals("Синтетический Студент", detail.user.name)
        assertEquals(listOf("P9999"), detail.user.groups.map { it.name })
        assertEquals(listOf("P9201", "P9101"), detail.groups.map { it.name })
        assertEquals(listOf("MODERATOR"), detail.roles)
        assertEquals(NOW, detail.createdAt)
        assertEquals(listOf("Pixel", "Tablet"), detail.devices.map { it.name })
        assertEquals(NOW.minusSeconds(7200), detail.devices[0].lastLogin)
        assertEquals(1, detail.friendsCount)
        assertEquals(2, detail.linksCount)
        assertEquals(listOf(RestrictionCapability.VOTE, RestrictionCapability.REPORT), detail.restrictions.map { it.capability })
        assertEquals(listOf(true, false), detail.restrictions.map { it.active })
        assertEquals(admin.isu, detail.restrictions[1].revokedByIsu)
        assertEquals(case.id, detail.restrictions[0].caseId)
        assertEquals(NOW.minusSeconds(300), detail.lastSeen)

        val quiet = service.detail(admin.id, 958403)
        assertNull(quiet.lastSeen)
        assertEquals(0, quiet.friendsCount)
        assertTrue(quiet.devices.isEmpty() && quiet.restrictions.isEmpty() && quiet.roles.isEmpty())
        assertFailsWith<NotFoundException> { service.detail(admin.id, 958499) }
    }

    @Test
    fun `granting and revoking the moderator role is idempotent and audited once per change`() {
        val student = user(958501, "Будущий модератор")
        em.flush()

        assertEquals(listOf("MODERATOR"), service.grant(admin.id, 958501, "MODERATOR"))
        assertEquals(listOf("MODERATOR"), service.grant(admin.id, 958501, "MODERATOR"))
        val granted = audit.findPage(PageRequest.of(0, 10)).content.filter { it.target == "user:958501" }
        assertEquals(listOf("ROLE_GRANTED"), granted.map { it.action })
        assertEquals(admin.id, granted.single().actorId)
        assertEquals("role MODERATOR", granted.single().details)
        assertEquals(NOW, granted.single().createdAt)

        assertEquals(emptyList(), service.revoke(admin.id, 958501, "MODERATOR"))
        assertEquals(emptyList(), service.revoke(admin.id, 958501, "MODERATOR"))
        // A fixed clock gives both rows the same time; only the id breaks the tie.
        assertEquals(listOf("ROLE_GRANTED", "ROLE_REVOKED"),
            audit.findPage(PageRequest.of(0, 10)).content.filter { it.target == "user:958501" }.map { it.action }.sorted())
        assertEquals(emptyList(), service.search(admin.id, "958501", 0, 20).items.single().roles)
        assertNotNull(student)
    }

    @Test
    fun `admin role and unknown users are refused and only admins manage users`() {
        val moderator = user(958601, "Модератор")
        em.persistAndFlush(UserRoleEntity(UserRoleId(moderator.id, UserRole.MODERATOR), NOW))

        assertFailsWith<InvalidRequestDataException> { service.grant(admin.id, 958601, "ADMIN") }
        assertFailsWith<InvalidRequestDataException> { service.revoke(admin.id, 958100, "ADMIN") }
        assertFailsWith<InvalidRequestDataException> { service.grant(admin.id, 958601, "moderator") }
        assertFailsWith<NotFoundException> { service.grant(admin.id, 958699, "MODERATOR") }
        assertFailsWith<PermissionDeniedException> { service.search(moderator.id, null, 0, 20) }
        assertFailsWith<PermissionDeniedException> { service.detail(moderator.id, 958100) }
        assertFailsWith<PermissionDeniedException> { service.grant(moderator.id, 958601, "MODERATOR") }
        assertFailsWith<PermissionDeniedException> { service.revoke(moderator.id, 958601, "MODERATOR") }
        assertTrue(audit.findPage(PageRequest.of(0, 50)).content.none { it.actorId == moderator.id || it.target == "user:958601" })
    }

    private fun user(isu: Int, name: String, createdAt: Instant = NOW, groups: List<GroupEntity> = emptyList()) =
        em.persist(User(isu = isu, name = name, pictureUrl = null, createdAt = createdAt).apply {
            settings = UserSettingsEntity(user = this)
            this.groups.addAll(groups)
        })

    private fun group(name: String, course: Int) = em.find(GroupEntity::class.java, UUID.nameUUIDFromBytes(name.toByteArray()))
        ?: em.persist(GroupEntity(id = UUID.nameUUIDFromBytes(name.toByteArray()), name = name, course = course,
            qualification = qualification, faculty = faculty))

    private fun link(owner: User) {
        val url = "https://example.org/${UUID.randomUUID()}"
        em.persist(SubjectLinkEntity(id = UUID.randomUUID(), owner = owner, subjectId = 42, subjectName = "Предмет", periodKey = "2026-1",
            category = LinkCategory.MATERIALS, url = url, normalizedUrl = url, title = null, visibility = LinkVisibility.PRIVATE,
            createdAt = NOW, updatedAt = NOW))
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
        val SAMPLE = UserData(0, "", null, emptyList(), dev.alllexey.itmowidgets.backend.dto.UserCapabilities(false, false, false))
    }
}
