package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.persistence.AdminAuditRepository
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAppVersionRequest
import dev.alllexey.itmowidgets.backend.feature.app.service.AppConfig
import dev.alllexey.itmowidgets.backend.feature.app.service.AppVersionSettings
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialStore
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateLog
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateOutcome
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleId
import dev.alllexey.itmowidgets.backend.feature.users.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

/** Refresh logs in the far future are the newest rows whatever other test classes committed. */
@Import(
    AdminSystemService::class,
    AppVersionSettings::class,
    AdminAccess::class,
    AdminAuditService::class,
    AdminUserSummaries::class,
    ServiceCredentialStore::class,
    AdminSystemServiceTest.TestConfig::class,
)
@TestPropertySource(properties = ["itmowidgets.app.version=2.1", "itmowidgets.app.min-version=2.0", "itmowidgets.app.note=Из окружения"])
class AdminSystemServiceTest @Autowired constructor(
    private val service: AdminSystemService,
    private val versions: AppVersionSettings,
    private val audit: AdminAuditRepository,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppConfig::class)
    class TestConfig {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    private lateinit var admin: User
    private lateinit var moderator: User

    @BeforeEach
    fun fixture() {
        admin = user(961001)
        moderator = user(961002)
        em.persist(UserRoleEntity(UserRoleId(admin.id, UserRole.ADMIN), NOW))
        em.persistAndFlush(UserRoleEntity(UserRoleId(moderator.id, UserRole.MODERATOR), NOW))
    }

    @Test
    fun `sport status lists newest runs and seven day outcomes errors and durations`() {
        log(NOW.minus(Duration.ofHours(1)), SportUpdateOutcome.SUCCESS, 1000)
        log(NOW.minus(Duration.ofHours(2)), SportUpdateOutcome.FAILED, 3000, SportUpdateErrorCategory.NETWORK)
        log(NOW.minus(Duration.ofHours(3)), SportUpdateOutcome.PARTIAL, 2000, SportUpdateErrorCategory.MAPPING)
        log(NOW.minus(Duration.ofDays(2)), SportUpdateOutcome.SUCCESS, 2000)
        log(NOW.minus(Duration.ofDays(8)), SportUpdateOutcome.FAILED, 9000, SportUpdateErrorCategory.AUTH)
        em.flush()
        em.clear()

        val status = service.sport(admin.id)
        assertEquals(NOW.minus(Duration.ofHours(1)), status.runs[0].timestamp)
        assertEquals(
            listOf(
                SportUpdateOutcome.SUCCESS,
                SportUpdateOutcome.FAILED,
                SportUpdateOutcome.PARTIAL,
                SportUpdateOutcome.SUCCESS,
                SportUpdateOutcome.FAILED,
            ),
            status.runs.take(5).map { it.outcome },
        )
        assertEquals(SportUpdateErrorCategory.NETWORK, status.runs[1].errorCategory)
        assertEquals(3000, status.runs[1].durationMillis)
        assertTrue(status.runs.size <= 50)
        assertEquals(
            mapOf(SportUpdateOutcome.SUCCESS to 2L, SportUpdateOutcome.PARTIAL to 1L, SportUpdateOutcome.FAILED to 1L),
            status.outcomes7d,
        )
        assertEquals(SportUpdateErrorCategory.entries.toSet(), status.errors7d.keys)
        assertEquals(1, status.errors7d[SportUpdateErrorCategory.NETWORK])
        assertEquals(1, status.errors7d[SportUpdateErrorCategory.MAPPING])
        assertEquals(0, status.errors7d[SportUpdateErrorCategory.AUTH])
        assertEquals(2000, status.averageDurationMillis7d)
        assertEquals(NOW.minus(Duration.ofHours(1)), status.lastSuccessAt)
        assertTrue(status.activeAutoSignEntries >= 0 && status.activeFreeSignEntries >= 0)
        assertFailsWith<PermissionDeniedException> { service.sport(moderator.id) }
    }

    @Test
    fun `app version falls back to the environment until an admin stores it and each change is audited once`() {
        val initial = service.appVersion(admin.id)
        assertEquals("2.1", initial.latest)
        assertEquals("2.0", initial.minimum)
        assertEquals("Из окружения", initial.note)
        assertFalse(initial.overridden)
        assertNull(initial.updatedAt)

        val stored = service.updateAppVersion(admin.id, AdminAppVersionRequest(" 2.3 ", "2.1", "Новая версия"))
        assertEquals("2.3", stored.latest)
        assertEquals("2.1", stored.minimum)
        assertEquals("Новая версия", stored.note)
        assertTrue(stored.overridden)
        assertEquals(NOW, stored.updatedAt)
        assertEquals(AppVersionSettings.AppVersion("2.3", "2.1", "Новая версия"), versions.current())
        service.updateAppVersion(admin.id, AdminAppVersionRequest("2.3", "2.1", "Новая версия"))
        service.updateAppVersion(admin.id, AdminAppVersionRequest("2.3", "2.3", "Новая версия"))

        val entries = audit.findPage(PageRequest.of(0, 20)).content.filter { it.actorId == admin.id }
        assertEquals(listOf("APP_VERSION_CHANGED", "APP_VERSION_CHANGED"), entries.map { it.action })
        assertEquals(setOf("latest 2.1 -> 2.3; minimum 2.0 -> 2.1; note changed", "minimum 2.1 -> 2.3"), entries.map { it.details }.toSet())
        assertEquals("app-version", entries.first().target)
    }

    @Test
    fun `invalid versions and non admins never store anything`() {
        for (request in listOf(
            AdminAppVersionRequest("2.1", "2.2"),
            AdminAppVersionRequest("", "2.1"),
            AdminAppVersionRequest("2.x", "2.1"),
            AdminAppVersionRequest("2.10", "2.9-beta", "x".repeat(501)),
            AdminAppVersionRequest("2.10", "2.10.1"),
        )) {
            assertFailsWith<InvalidRequestDataException>(request.toString()) { service.updateAppVersion(admin.id, request) }
        }
        assertEquals("2.10", service.updateAppVersion(admin.id, AdminAppVersionRequest("2.10", "2.9-beta")).latest)
        assertFailsWith<PermissionDeniedException> { service.appVersion(moderator.id) }
        assertFailsWith<PermissionDeniedException> { service.updateAppVersion(moderator.id, AdminAppVersionRequest("2.4", "2.1")) }
        assertEquals("2.10", versions.current().latest)
    }

    private fun user(isu: Int) = em.persist(
        User(isu = isu, name = "Synthetic user", pictureUrl = null, createdAt = NOW).apply {
            settings = UserSettingsEntity(user = this)
        },
    )

    private fun log(at: Instant, outcome: SportUpdateOutcome, duration: Long, error: SportUpdateErrorCategory? = null) = em.persist(
        SportUpdateLog(
            updateTimestamp = at,
            outcome = outcome,
            durationMillis = duration,
            receivedLessons = 10,
            newLessonsAdded = 1,
            updatedLessons = 2,
            skippedLessons = 0,
            errorCategory = error,
        ),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2031-03-15T10:00:00Z")
    }
}
