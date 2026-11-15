package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.model.AdminAuditEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewProvider
import dev.alllexey.itmowidgets.backend.feature.reviews.service.OfficialPersonNamesSource
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherDisplayNames
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherNamesService
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleId
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.testing.persistUser
import dev.alllexey.itmowidgets.backend.testing.statements
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

@Import(
    AdminAuditPageService::class,
    AdminAccess::class,
    AdminUserSummaries::class,
    TeacherDisplayNames::class,
    TeacherNamesService::class,
    AdminAuditPageServiceTest.TestConfig::class,
)
class AdminAuditPageServiceTest @Autowired constructor(private val service: AdminAuditPageService, private val em: TestEntityManager) :
    PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    class TestConfig {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean fun personNames() = OfficialPersonNamesSource { isu -> OFFICIAL_NAME.takeIf { isu == OFFICIAL_TEACHER } }
    }

    private lateinit var admin: User
    private lateinit var other: User
    private lateinit var moderator: User

    @BeforeEach
    fun fixture() {
        admin = em.persistUser(957100, "Администратор Первый", createdAt = NOW)
        other = em.persistUser(957101, "Администратор Второй", createdAt = NOW)
        moderator = em.persistUser(957102, "Модератор", createdAt = NOW)
        em.persist(UserRoleEntity(UserRoleId(admin.id, UserRole.ADMIN), NOW))
        em.persist(UserRoleEntity(UserRoleId(other.id, UserRole.ADMIN), NOW))
        em.persistAndFlush(UserRoleEntity(UserRoleId(moderator.id, UserRole.MODERATOR), NOW))
    }

    @Test
    fun `filters combine by AND over the actor, action, target and Moscow days, and the total counts the filtered list`() {
        val entries = sevenEntries()
        fun ids(filter: AdminAuditFilter) = service.page(admin.id, filter, 0, 20).items.map { it.id }
        fun mine(filter: AdminAuditFilter = AdminAuditFilter()) = ids(filter.copy(actorIsu = admin.isu))
        fun theirs(filter: AdminAuditFilter = AdminAuditFilter()) = ids(filter.copy(actorIsu = other.isu))

        assertEquals(entries.slice(listOf(1, 2, 3, 5, 6)), mine())
        assertEquals(entries.slice(listOf(0, 4)), theirs())
        assertEquals(emptyList(), ids(AdminAuditFilter(actorIsu = moderator.isu)))
        assertEquals(entries.slice(listOf(1)), mine(AdminAuditFilter(action = AdminAuditAction.ROLE_GRANTED)))
        assertEquals(emptyList(), mine(AdminAuditFilter(action = AdminAuditAction.ROLE_REVOKED)))

        assertEquals(entries.slice(listOf(1, 6)), mine(AdminAuditFilter(targetType = "user")))
        assertEquals(entries.slice(listOf(2, 3)), mine(AdminAuditFilter(targetType = "teacher")))
        assertEquals(entries.slice(listOf(5)), mine(AdminAuditFilter(targetType = "moderation-settings")))
        assertEquals(entries.slice(listOf(4)), theirs(AdminAuditFilter(targetType = "credential")))
        // The type is the whole part before the colon, compared literally.
        for (type in listOf("use", "use_", "us%", "user:$NAMED", "moderation")) {
            assertEquals(emptyList(), mine(AdminAuditFilter(targetType = type)), type)
        }
        assertEquals(entries.slice(listOf(1)), ids(AdminAuditFilter(target = "user:$NAMED")))
        assertEquals(emptyList(), ids(AdminAuditFilter(target = "user:${NAMED / 10}")))
        assertEquals(emptyList(), mine(AdminAuditFilter(targetType = "teacher", target = "user:$NAMED")))

        // 2026-09-23T20:59:59Z is the last second of 23 September in Moscow, 21:00:00Z the first of the 24th.
        assertEquals(entries.slice(listOf(1)), mine(AdminAuditFilter(from = DAY, to = DAY)))
        assertEquals(entries.slice(listOf(0)), theirs(AdminAuditFilter(from = DAY.plusDays(1))))
        assertEquals(entries.slice(listOf(4)), theirs(AdminAuditFilter(to = DAY)))
        assertEquals(entries.slice(listOf(2, 3)), mine(AdminAuditFilter(from = DAY.minusDays(2), to = DAY.minusDays(1))))
        assertEquals(emptyList(), mine(AdminAuditFilter(from = DAY.plusDays(1))))

        val paged = service.page(admin.id, AdminAuditFilter(actorIsu = admin.isu, targetType = "user"), 1, 1)
        assertEquals(entries.slice(listOf(6)), paged.items.map { it.id })
        assertEquals(2, paged.total)
        assertEquals(5, service.page(admin.id, AdminAuditFilter(actorIsu = admin.isu), 0, 1).total)

        assertFailsWith<InvalidRequestDataException> { service.page(admin.id, AdminAuditFilter(from = DAY, to = DAY.minusDays(1)), 0, 20) }
        assertFailsWith<PermissionDeniedException> { service.page(moderator.id, AdminAuditFilter(), 0, 20) }
    }

    @Test
    fun `entries name their user from the account and their teacher from a Reviews copy or the official directory`() {
        sevenEntries()
        val page = service.page(admin.id, AdminAuditFilter(from = DAY.minusDays(10)), 0, 100).items
            .filter { it.actorIsu == admin.isu || it.actorIsu == other.isu }
        val names = page.associate { it.target to it.targetName }

        assertEquals("Названный Пользователь", names["user:$NAMED"])
        assertNull(names.getValue("user:$NAMELESS"), "A user without a stored name")
        assertNull(names.getValue("user:$GONE"), "A deleted account")
        assertEquals("Имя из Reviews", names["teacher:$COPY_TEACHER"])
        assertEquals(OFFICIAL_NAME, names["teacher:$OFFICIAL_TEACHER"])
        assertNull(names.getValue("credential:GEMINI_API_KEY"))
        assertNull(names.getValue("moderation-settings"))
        assertEquals(admin.name, page.first { it.target == "user:$NAMED" }.actorName)
    }

    @Test
    fun `a page takes the same number of statements for one named user or many`() {
        val named = em.persistUser(957401, "Пользователь", createdAt = NOW)
        entry(admin, AdminAuditAction.ROLE_GRANTED, "user:${named.isu}", NOW)
        em.flush()
        em.clear()
        val filter = AdminAuditFilter(targetType = "user", from = DAY)
        val single = em.statements { service.page(admin.id, filter, 0, 20) }
        for (isu in 957402..957406) {
            em.persistUser(isu, "Пользователь $isu", createdAt = NOW)
            entry(if (isu % 2 == 0) admin else other, AdminAuditAction.ROLE_REVOKED, "user:$isu", NOW)
        }
        em.flush()
        em.clear()
        val many = em.statements { service.page(admin.id, filter, 0, 20) }
        assertEquals(single, many)
    }

    /** Newest first, as the page lists them; the first two straddle midnight in Moscow. */
    private fun sevenEntries(): List<UUID> {
        em.persistUser(NAMED, "Названный Пользователь", createdAt = NOW)
        em.persistUser(NAMELESS, null, createdAt = NOW)
        em.persist(
            ExternalTeacherReviewEntity(
                provider = ReviewProvider.REVIEWS_WORK_GD, externalId = 9_570_001, teacherIsu = COPY_TEACHER,
                teacherName = "Имя из Reviews", subjectTitle = null, sourceTitle = null, sourceLink = null, dateRaw = "",
                writtenOn = null, writtenBeforeYear = null, text = "Синтетическая копия", firstSeenAt = NOW, lastSeenAt = NOW,
            ),
        )
        val ids = listOf(
            entry(other, AdminAuditAction.ROLE_REVOKED, "user:$NAMELESS", Instant.parse("2026-09-23T21:00:00Z")),
            entry(admin, AdminAuditAction.ROLE_GRANTED, "user:$NAMED", Instant.parse("2026-09-23T20:59:59Z")),
            entry(admin, AdminAuditAction.AI_SUMMARY_HIDDEN, "teacher:$COPY_TEACHER", Instant.parse("2026-09-22T10:00:00Z")),
            entry(admin, AdminAuditAction.AI_SUMMARY_SHOWN, "teacher:$OFFICIAL_TEACHER", Instant.parse("2026-09-21T10:00:00Z")),
            entry(other, AdminAuditAction.SERVICE_CREDENTIAL_REPLACED, "credential:GEMINI_API_KEY", Instant.parse("2026-09-20T10:00:00Z")),
            entry(admin, AdminAuditAction.MODERATION_SETTINGS_CHANGED, "moderation-settings", Instant.parse("2026-09-19T10:00:00Z")),
            entry(admin, AdminAuditAction.AGENT_ACCESS_GRANTED, "user:$GONE", Instant.parse("2026-09-18T10:00:00Z")),
        )
        em.flush()
        em.clear()
        return ids
    }

    private fun entry(actor: User, action: AdminAuditAction, target: String, at: Instant): UUID =
        em.persist(AdminAuditEntity(actorId = actor.id, action = action.name, target = target, details = null, createdAt = at)).id

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T09:00:00Z")
        val DAY: LocalDate = LocalDate.parse("2026-09-23")
        const val NAMED = 957201
        const val NAMELESS = 957202
        const val GONE = 957999
        const val COPY_TEACHER = 957301
        const val OFFICIAL_TEACHER = 957302
        const val OFFICIAL_NAME = "Официальный Преподаватель Тестович"
    }
}
