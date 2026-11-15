package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.persistence.AdminAuditRepository
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAgentAccess
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientHeader
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientKind
import dev.alllexey.itmowidgets.backend.feature.clients.persistence.ClientInstanceRepository
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientAccessService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionService
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkCategory
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkVisibility
import dev.alllexey.itmowidgets.backend.feature.links.model.SubjectLinkEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.moderation.model.UserRestrictionEntity
import dev.alllexey.itmowidgets.backend.feature.push.model.Device
import dev.alllexey.itmowidgets.backend.feature.social.model.FriendshipEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.FacultyEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.GroupEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.QualificationEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleId
import dev.alllexey.itmowidgets.backend.feature.users.web.GroupData
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.testing.persistUser
import dev.alllexey.itmowidgets.backend.testing.statements
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

@Import(
    AdminUsersService::class,
    AdminAccess::class,
    AdminUserSummaries::class,
    AdminRestrictionViews::class,
    AdminAuditService::class,
    AdminUsersServiceTest.TimeConfig::class,
)
class AdminUsersServiceTest @Autowired constructor(
    private val service: AdminUsersService,
    private val audit: AdminAuditRepository,
    private val clientInstances: ClientInstanceRepository,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var clientAccess: ClientAccessService

    @MockitoBean private lateinit var connections: ConnectionService

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    private lateinit var admin: User
    private lateinit var faculty: FacultyEntity
    private lateinit var qualification: QualificationEntity

    @BeforeEach
    fun fixture() {
        faculty = em.persist(FacultyEntity(958_001, "Synthetic faculty", "СИН"))
        qualification = em.persist(QualificationEntity(958_001, "Synthetic"))
        admin = em.persistUser(958100, "Администратор Синтетический", createdAt = NOW)
        em.persistAndFlush(UserRoleEntity(UserRoleId(admin.id, UserRole.ADMIN), NOW))
    }

    @Test
    fun `search matches an ISU prefix a name part in any case and a stored group newest first`() {
        val older = em.persistUser(
            958201,
            "Иванова Анна",
            createdAt = NOW.minusSeconds(3600),
            groups = listOf(group("P9101", 1), group("P9201", 2)),
        )
        val newer = em.persistUser(958202, "Петров Пётр", createdAt = NOW.minusSeconds(60), groups = listOf(group("Z9301", 3)))
        em.persistUser(958303, "Сидоров_Иван", createdAt = NOW)
        em.persistAndFlush(UserRoleEntity(UserRoleId(newer.id, UserRole.MODERATOR), NOW))
        em.flush()
        em.clear()

        val byIsu = service.search(admin.id, "95820", null, null, 0, 20)
        assertEquals(listOf(958202, 958201), byIsu.items.map { it.isu })
        assertEquals(2, byIsu.total)
        assertEquals(listOf("MODERATOR"), byIsu.items[0].roles)
        assertEquals(emptyList(), byIsu.items[1].roles)
        assertEquals(listOf("P9201", "P9101"), byIsu.items[1].groups.map { it.name })
        assertEquals(GroupData("P9201", 2, "СИН"), byIsu.items[1].groups[0])
        assertEquals(older.createdAt, byIsu.items[1].createdAt)

        assertEquals(listOf(958201), service.search(admin.id, "  ИВАНОВА ", null, null, 0, 20).items.map { it.isu })
        assertEquals(listOf(958202), service.search(admin.id, "z93", null, null, 0, 20).items.map { it.isu })
        // LIKE wildcards in the query are literal characters.
        assertEquals(listOf(958303), service.search(admin.id, "в_и", null, null, 0, 20).items.map { it.isu })
        assertTrue(service.search(admin.id, "%", null, null, 0, 20).items.isEmpty())

        val paged = service.search(admin.id, "9582", null, null, 1, 1)
        assertEquals(listOf(958201), paged.items.map { it.isu })
        assertEquals(1, paged.page)
        assertEquals(1, paged.size)
        assertEquals(2, paged.total)
        assertTrue(
            service.search(admin.id, null, null, null, 0, 100).items.map {
                it.isu
            }.containsAll(listOf(958100, 958201, 958202, 958303)),
        )
        assertFailsWith<InvalidRequestDataException> { service.search(admin.id, null, null, null, 0, 101) }
        assertFailsWith<InvalidRequestDataException> { service.search(admin.id, null, null, null, -1, 20) }
    }

    @Test
    fun `the list counts restrictions in force and filters by role and restriction with the query`() {
        val moderator = em.persistUser(958701, "Модератор Синтетический", createdAt = NOW.minusSeconds(5000))
        val other = em.persistUser(958702, "Админ Синтетический", createdAt = NOW.minusSeconds(4000))
        val revoked = em.persistUser(958703, "Помилованный", createdAt = NOW.minusSeconds(3000))
        val restricted = em.persistUser(958704, "Ограниченный", createdAt = NOW.minusSeconds(2000))
        val clean = em.persistUser(958705, "Чистый", createdAt = NOW.minusSeconds(1000))
        em.persist(UserRoleEntity(UserRoleId(moderator.id, UserRole.MODERATOR), NOW))
        em.persist(UserRoleEntity(UserRoleId(other.id, UserRole.ADMIN), NOW))
        val decision = restrictDecision()
        restriction(moderator, decision, RestrictionCapability.VOTE, NOW.minusSeconds(100), expiresAt = NOW.plusSeconds(60))
        restriction(moderator, decision, RestrictionCapability.REPORT, NOW)
        restriction(other, decision, RestrictionCapability.VOTE, NOW.minusSeconds(86_400), expiresAt = NOW)
        restriction(revoked, decision, RestrictionCapability.ALL, NOW.minusSeconds(100), revokedAt = NOW.minusSeconds(10))
        restriction(revoked, decision, RestrictionCapability.ALL, NOW.plusSeconds(60))
        restriction(restricted, decision, RestrictionCapability.ALL, NOW.minusSeconds(100))
        em.flush()
        em.clear()
        fun isus(query: String? = "9587", role: UserRole? = null, restrictedOnly: Boolean? = null) =
            service.search(admin.id, query, role, restrictedOnly, 0, 20).items.map { it.isu }

        val everybody = service.search(admin.id, "9587", null, null, 0, 20)
        assertEquals(listOf(958705, 958704, 958703, 958702, 958701), everybody.items.map { it.isu })
        assertEquals(listOf(0, 1, 0, 0, 2), everybody.items.map { it.activeRestrictions })
        assertEquals(listOf(958701), isus(role = UserRole.MODERATOR))
        assertEquals(listOf(958702), isus(role = UserRole.ADMIN))
        assertEquals(listOf(958704, 958701), isus(restrictedOnly = true))
        assertEquals(listOf(958705, 958703, 958702), isus(restrictedOnly = false))
        assertEquals(listOf(958701), isus(role = UserRole.MODERATOR, restrictedOnly = true))
        assertEquals(emptyList(), isus(role = UserRole.MODERATOR, restrictedOnly = false))
        assertEquals(listOf(958701), isus(query = "модератор", restrictedOnly = true))
        assertEquals(emptyList(), isus(query = "чистый", restrictedOnly = true))
        assertTrue(isus(query = null, role = UserRole.ADMIN).containsAll(listOf(958100, 958702)))
        assertTrue(isus(query = null, restrictedOnly = true).containsAll(listOf(958701, 958704)))

        val paged = service.search(admin.id, "9587", null, true, 1, 1)
        assertEquals(listOf(958701), paged.items.map { it.isu })
        assertEquals(2, paged.total)
        assertEquals(3, service.search(admin.id, "9587", null, false, 0, 1).total)
        assertEquals(1, service.search(admin.id, "9587", UserRole.MODERATOR, null, 0, 1).total)
    }

    @Test
    fun `a list page takes the same number of statements for one user or many`() {
        val decision = restrictDecision()
        val first = em.persistUser(958801, "Первый", createdAt = NOW)
        restriction(first, decision, RestrictionCapability.VOTE, NOW.minusSeconds(10))
        em.flush()
        em.clear()
        val single = em.statements { service.search(admin.id, "9588", null, null, 0, 20) }
        for (isu in 958802..958806) {
            val user = em.persistUser(isu, "Пользователь $isu", createdAt = NOW, groups = listOf(group("P${isu % 10}101", 1)))
            em.persist(UserRoleEntity(UserRoleId(user.id, UserRole.MODERATOR), NOW))
            restriction(user, decision, RestrictionCapability.REPORT, NOW.minusSeconds(10))
        }
        em.flush()
        em.clear()
        var size = 0
        val many = em.statements { size = service.search(admin.id, "9588", null, null, 0, 20).items.size }
        assertEquals(6, size)
        assertEquals(single, many)
    }

    @Test
    fun `detail joins roles devices friends links restrictions and the latest activity`() {
        val student = em.persistUser(
            958401,
            "Синтетический Студент",
            createdAt = NOW,
            groups = listOf(group("P9101", 1), group("P9201", 2)),
        )
        val friend = em.persistUser(958402, "Друг", createdAt = NOW)
        val stranger = em.persistUser(958403, "Незнакомец", createdAt = NOW)
        em.persist(UserRoleEntity(UserRoleId(student.id, UserRole.MODERATOR), NOW))
        em.persist(
            FriendshipEntity(
                requester = student,
                addressee = friend,
                status = FriendshipEntity.Status.ACCEPTED,
                createdAt = NOW.minusSeconds(100),
                respondedAt = NOW.minusSeconds(50),
            ),
        )
        em.persist(FriendshipEntity(requester = stranger, addressee = student, createdAt = NOW.minusSeconds(100)))
        em.persist(Device(user = student, fcmToken = "synthetic-token-1", deviceName = "Pixel", lastLogin = NOW.minusSeconds(7200)))
        em.persist(
            Device(user = student, fcmToken = "synthetic-token-2", deviceName = "Tablet", lastLogin = NOW.minusSeconds(86_400))
                .apply { platform = AppPlatform.IOS },
        )
        link(student)
        link(student)
        val case = em.persist(
            ModerationCaseEntity(
                targetType = ModerationTargetType.SUBJECT_RESOURCE,
                targetId = UUID.randomUUID(),
                reason = ModerationCaseReason.REPORTS,
                openedAt = NOW.minusSeconds(900),
            ),
        )
        val decision = em.persist(
            ModerationDecisionEntity(
                case = case,
                moderator = admin,
                action = ModerationAction.RESTRICT_USER,
                restrictionCapability = RestrictionCapability.VOTE,
                restrictionDays = 7,
                createdAt = NOW.minusSeconds(800),
            ),
        )
        em.persist(
            UserRestrictionEntity(
                user = student,
                capability = RestrictionCapability.VOTE,
                decision = decision,
                reason = "Спам",
                startsAt = NOW.minusSeconds(800),
                expiresAt = NOW.plusSeconds(86_400),
            ),
        )
        em.persist(
            UserRestrictionEntity(
                user = student,
                capability = RestrictionCapability.REPORT,
                decision = decision,
                reason = "Старое",
                startsAt = NOW.minusSeconds(86_400 * 3L),
                revokedAt = NOW.minusSeconds(86_400),
                revokedBy = admin,
            ),
        )
        em.flush()
        em.clear()
        `when`(connections.lastUsed(student.id)).thenReturn(NOW.minusSeconds(300))

        val detail = service.detail(admin.id, 958401)
        assertEquals(958401, detail.user.isu)
        assertEquals("Синтетический Студент", detail.user.name)
        assertEquals(listOf("P9201", "P9101"), detail.user.groups.map { it.name })
        assertEquals(listOf("P9201", "P9101"), detail.groups.map { it.name })
        assertEquals(listOf("MODERATOR"), detail.roles)
        assertEquals(NOW, detail.createdAt)
        assertEquals(listOf("Pixel", "Tablet"), detail.devices.map { it.name })
        assertEquals(listOf(AppPlatform.ANDROID, AppPlatform.IOS), detail.devices.map { it.platform })
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
    fun `detail lists the user's clients newest first with agent names and counts them as last seen`() {
        val student = em.persistUser(958411, createdAt = NOW)
        val token = UUID.randomUUID()
        `when`(connections.namesOf(listOf(token))).thenReturn(mapOf(token to "Synthetic agent"))
        em.flush()
        fun client(kind: ClientKind, instance: UUID, header: ClientHeader, seen: Instant) = clientInstances.upsert(
            UUID.randomUUID(), student.id, kind.name, instance, header.version, header.build, header.channel, header.source,
            header.commit, header.base, header.os, header.browser, header.custom, seen,
        )
        client(
            ClientKind.ANDROID,
            UUID.randomUUID(),
            ClientHeader(ClientKind.ANDROID, "2.3.0", "20300", "play", os = "36"),
            NOW.minusSeconds(60),
        )
        client(
            ClientKind.AGENT,
            token,
            ClientHeader(ClientKind.AGENT, "1.4.0", channel = "custom", source = "local"),
            NOW.minusSeconds(3600),
        )

        val detail = service.detail(admin.id, 958411)

        assertEquals(listOf(ClientKind.ANDROID, ClientKind.AGENT), detail.clients.map { it.kind })
        assertEquals(listOf(null, "Synthetic agent"), detail.clients.map { it.name })
        assertEquals(listOf(false, true), detail.clients.map { it.custom })
        assertEquals("36", detail.clients[0].os)
        assertEquals(NOW.minusSeconds(60), detail.lastSeen)
    }

    @Test
    fun `granting and revoking the moderator role is idempotent and audited once per change`() {
        val student = em.persistUser(958501, "Будущий модератор", createdAt = NOW)
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
        assertEquals(
            listOf("ROLE_GRANTED", "ROLE_REVOKED"),
            audit.findPage(PageRequest.of(0, 10)).content.filter { it.target == "user:958501" }.map { it.action }.sorted(),
        )
        assertEquals(emptyList(), service.search(admin.id, "958501", null, null, 0, 20).items.single().roles)
        assertNotNull(student)
    }

    @Test
    fun `agent access is audited once per change and kept out of other users' hands`() {
        val student = em.persistUser(958511, "Бета-тестер", createdAt = NOW)
        em.flush()
        `when`(clientAccess.setAgentGranted(student.id, true)).thenReturn(true, false)

        assertEquals(AdminAgentAccess(true), service.setAgentAccess(admin.id, 958511, true))
        assertEquals(AdminAgentAccess(true), service.setAgentAccess(admin.id, 958511, true))

        val rows = audit.findPage(PageRequest.of(0, 10)).content.filter { it.target == "user:958511" }
        assertEquals(listOf("AGENT_ACCESS_GRANTED"), rows.map { it.action })
        assertFailsWith<PermissionDeniedException> { service.setAgentAccess(student.id, 958511, true) }
    }

    @Test
    fun `admin role and unknown users are refused and only admins manage users`() {
        val moderator = em.persistUser(958601, "Модератор", createdAt = NOW)
        em.persistAndFlush(UserRoleEntity(UserRoleId(moderator.id, UserRole.MODERATOR), NOW))

        assertFailsWith<InvalidRequestDataException> { service.grant(admin.id, 958601, "ADMIN") }
        assertFailsWith<InvalidRequestDataException> { service.revoke(admin.id, 958100, "ADMIN") }
        assertFailsWith<InvalidRequestDataException> { service.grant(admin.id, 958601, "moderator") }
        assertFailsWith<NotFoundException> { service.grant(admin.id, 958699, "MODERATOR") }
        assertFailsWith<PermissionDeniedException> { service.search(moderator.id, null, null, null, 0, 20) }
        assertFailsWith<PermissionDeniedException> { service.detail(moderator.id, 958100) }
        assertFailsWith<PermissionDeniedException> { service.grant(moderator.id, 958601, "MODERATOR") }
        assertFailsWith<PermissionDeniedException> { service.revoke(moderator.id, 958601, "MODERATOR") }
        assertTrue(audit.findPage(PageRequest.of(0, 50)).content.none { it.actorId == moderator.id || it.target == "user:958601" })
    }

    private fun restrictDecision(): ModerationDecisionEntity {
        val case = em.persist(
            ModerationCaseEntity(
                targetType = ModerationTargetType.SUBJECT_RESOURCE,
                targetId = UUID.randomUUID(),
                reason = ModerationCaseReason.REPORTS,
                openedAt = NOW.minusSeconds(900),
            ),
        )
        return em.persist(
            ModerationDecisionEntity(
                case = case,
                moderator = admin,
                action = ModerationAction.RESTRICT_USER,
                restrictionCapability = RestrictionCapability.ALL,
                createdAt = NOW.minusSeconds(800),
            ),
        )
    }

    private fun restriction(
        user: User,
        decision: ModerationDecisionEntity,
        capability: RestrictionCapability,
        startsAt: Instant,
        expiresAt: Instant? = null,
        revokedAt: Instant? = null,
    ) = em.persist(
        UserRestrictionEntity(
            user = user,
            capability = capability,
            decision = decision,
            reason = "Синтетическая причина",
            startsAt = startsAt,
            expiresAt = expiresAt,
            revokedAt = revokedAt,
            revokedBy = admin.takeIf { revokedAt != null },
        ),
    )

    private fun group(name: String, course: Int) = em.find(GroupEntity::class.java, UUID.nameUUIDFromBytes(name.toByteArray()))
        ?: em.persist(
            GroupEntity(
                id = UUID.nameUUIDFromBytes(name.toByteArray()),
                name = name,
                course = course,
                qualification = qualification,
                faculty = faculty,
            ),
        )

    private fun link(owner: User) {
        val url = "https://example.org/${UUID.randomUUID()}"
        em.persist(
            SubjectLinkEntity(
                id = UUID.randomUUID(), owner = owner, subjectId = 42, subjectName = "Предмет", periodKey = "2026-1",
                category = LinkCategory.MATERIALS, url = url, normalizedUrl = url, title = null, visibility = LinkVisibility.PRIVATE,
                createdAt = NOW, updatedAt = NOW,
            ),
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
    }
}
