package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.persistence.AdminAuditRepository
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAppVersionRequest
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClientBuild
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClientBuildGroup
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClientCount
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminClientVersionWindow
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform.ANDROID
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform.IOS
import dev.alllexey.itmowidgets.backend.feature.app.service.AppConfig
import dev.alllexey.itmowidgets.backend.feature.app.service.AppVersionSettings
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientHeader
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientKind
import dev.alllexey.itmowidgets.backend.feature.clients.persistence.ClientInstanceRepository
import dev.alllexey.itmowidgets.backend.feature.credentials.service.ServiceCredentialStore
import dev.alllexey.itmowidgets.backend.feature.push.model.ClientVersion
import dev.alllexey.itmowidgets.backend.feature.push.model.Device
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateLog
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateOutcome
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRoleId
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.testing.persistUser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
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
@TestPropertySource(
    properties = [
        "itmowidgets.app.version=2.1", "itmowidgets.app.min-version=2.0", "itmowidgets.app.note=Из окружения",
        "itmowidgets.app.ios.version=2.3", "itmowidgets.app.ios.min-version=2.3", "itmowidgets.app.ios.note=",
    ],
)
class AdminSystemServiceTest @Autowired constructor(
    private val service: AdminSystemService,
    private val versions: AppVersionSettings,
    private val audit: AdminAuditRepository,
    private val clientInstances: ClientInstanceRepository,
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
        admin = em.persistUser(961001, createdAt = NOW)
        moderator = em.persistUser(961002, createdAt = NOW)
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
        val initial = service.appVersion(admin.id, ANDROID)
        assertEquals("2.1", initial.latest)
        assertEquals("2.0", initial.minimum)
        assertEquals("Из окружения", initial.note)
        assertFalse(initial.overridden)
        assertNull(initial.updatedAt)

        val stored = service.updateAppVersion(admin.id, ANDROID, AdminAppVersionRequest(" 2.3 ", "2.1", "Новая версия"))
        assertEquals("2.3", stored.latest)
        assertEquals("2.1", stored.minimum)
        assertEquals("Новая версия", stored.note)
        assertTrue(stored.overridden)
        assertEquals(NOW, stored.updatedAt)
        assertEquals(AppVersionSettings.AppVersion("2.3", "2.1", "Новая версия"), versions.current(ANDROID))
        service.updateAppVersion(admin.id, ANDROID, AdminAppVersionRequest("2.3", "2.1", "Новая версия"))
        service.updateAppVersion(admin.id, ANDROID, AdminAppVersionRequest("2.3", "2.3", "Новая версия"))

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
            for (platform in listOf(ANDROID, IOS)) {
                assertFailsWith<InvalidRequestDataException>("$platform $request") { service.updateAppVersion(admin.id, platform, request) }
            }
        }
        assertEquals("2.10", service.updateAppVersion(admin.id, ANDROID, AdminAppVersionRequest("2.10", "2.9-beta")).latest)
        for (platform in listOf(ANDROID, IOS)) {
            assertFailsWith<PermissionDeniedException> { service.appVersion(moderator.id, platform) }
            assertFailsWith<PermissionDeniedException> {
                service.updateAppVersion(moderator.id, platform, AdminAppVersionRequest("2.4", "2.1"))
            }
        }
        assertEquals("2.10", versions.current(ANDROID).latest)
        assertEquals(AppVersionSettings.AppVersion("2.3", "2.3", ""), versions.current(IOS))
    }

    @Test
    fun `ios version is stored under its own keys and audited with its platform without touching android`() {
        val initial = service.appVersion(admin.id, IOS)
        assertEquals(listOf("2.3", "2.3", ""), listOf(initial.latest, initial.minimum, initial.note))
        assertFalse(initial.overridden)
        assertNull(initial.updatedAt)

        val stored = service.updateAppVersion(admin.id, IOS, AdminAppVersionRequest("2.4", "2.3", "Для iOS"))
        assertEquals(listOf("2.4", "2.3", "Для iOS"), listOf(stored.latest, stored.minimum, stored.note))
        assertTrue(stored.overridden)
        assertEquals(NOW, stored.updatedAt)
        service.updateAppVersion(admin.id, IOS, AdminAppVersionRequest("2.4", "2.3", "Для iOS"))

        val android = service.appVersion(admin.id, ANDROID)
        assertEquals(listOf("2.1", "2.0", "Из окружения"), listOf(android.latest, android.minimum, android.note))
        assertFalse(android.overridden)

        val entries = audit.findPage(PageRequest.of(0, 20)).content.filter { it.actorId == admin.id }
        assertEquals(listOf("APP_VERSION_CHANGED"), entries.map { it.action })
        assertEquals("app-version", entries.single().target)
        assertEquals("IOS: latest 2.3 -> 2.4; note changed", entries.single().details)
    }

    @Test
    fun `client versions count active devices per reported build with the unreported ones apart`() {
        val owner = em.persistUser(961003, createdAt = NOW)
        fun device(lastLogin: Duration, reported: ClientVersion? = null, seen: Duration = Duration.ZERO) = em.persist(
            Device(user = owner, fcmToken = "synthetic-${UUID.randomUUID()}", deviceName = "Synthetic", lastLogin = NOW.minus(lastLogin))
                .apply { if (reported != null) reportClientVersion(reported, NOW.minus(seen)) },
        )
        val github = ClientVersion("2.3.0-beta.1", 20291, ANDROID, "github")
        val play = ClientVersion("2.3.0-beta.0", 20290, ANDROID, "play")
        val ios = ClientVersion("2.3.0-beta.1", 20291, IOS, "appstore")
        device(Duration.ofDays(60), github, seen = Duration.ofDays(1))
        device(Duration.ofDays(60), github, seen = Duration.ofDays(10))
        device(Duration.ofDays(60), ios, seen = Duration.ofDays(2))
        // Registered recently, reported long ago: active by its registration, counted under its last build.
        device(Duration.ofDays(5), play, seen = Duration.ofDays(40))
        device(Duration.ofDays(3))
        device(Duration.ofDays(20))
        device(Duration.ofDays(40))
        em.flush()

        val versions = service.clientVersions(admin.id)

        val week = listOf(
            AdminClientBuild(ANDROID, "github", "2.3.0-beta.1", 20291, 1),
            AdminClientBuild(IOS, "appstore", "2.3.0-beta.1", 20291, 1),
            AdminClientBuild(ANDROID, "play", "2.3.0-beta.0", 20290, 1),
        )
        assertEquals(AdminClientVersionWindow(activeDevices = 4, unknownDevices = 1, builds = week), versions.last7d)
        val month = listOf(week[0].copy(devices = 2), week[1], week[2])
        assertEquals(AdminClientVersionWindow(activeDevices = 6, unknownDevices = 2, builds = month), versions.last30d)
        assertFailsWith<PermissionDeniedException> { service.clientVersions(moderator.id) }
    }

    @Test
    fun `clients count official and custom builds apart, by upstream version, with platforms and history`() {
        val owner = em.persistUser(961004, createdAt = NOW)
        em.flush()
        fun client(kind: ClientKind, header: ClientHeader, seen: Duration) = clientInstances.upsert(
            UUID.randomUUID(), owner.id, kind.name, UUID.randomUUID(), header.version, header.build, header.channel, header.source,
            header.commit, header.base, header.os, header.browser, header.custom, NOW.minus(seen),
        )
        val play = ClientHeader(ClientKind.ANDROID, "2.3.0", "20300", "play", os = "36")
        val fork = ClientHeader(ClientKind.ANDROID, "2.3.0-x", "1", "custom", source = "someone/ITMO.Widgets", base = "2.3.0", os = "35")
        client(ClientKind.ANDROID, play, Duration.ofHours(2))
        client(ClientKind.ANDROID, play, Duration.ofDays(3))
        client(ClientKind.ANDROID, fork, Duration.ofDays(20))
        client(ClientKind.WEB, ClientHeader(ClientKind.WEB, "abc123", channel = "web", browser = "chrome/131"), Duration.ofHours(1))
        client(ClientKind.ANDROID, play, Duration.ofDays(40))
        clientInstances.writeDaily(
            LocalDate.parse("2031-03-14"),
            NOW.minus(Duration.ofDays(1)),
            NOW.minus(Duration.ofDays(7)),
            NOW.minus(Duration.ofDays(30)),
        )

        val clients = service.clients(admin.id)

        assertEquals(listOf(ClientKind.ANDROID, ClientKind.WEB), clients.kinds.map { it.kind })
        val android = clients.kinds[0]
        assertEquals(listOf(1L, 2L, 3L), listOf(android.active1d, android.active7d, android.active30d))
        assertEquals(
            listOf(
                AdminClientBuildGroup(false, "2.3.0", "play", null, 2, 2),
                AdminClientBuildGroup(true, "2.3.0", "custom", "someone/ITMO.Widgets", 0, 1),
            ),
            android.builds,
        )
        assertEquals(listOf(AdminClientCount("36", 2), AdminClientCount("35", 1)), android.platforms)
        assertEquals(listOf(AdminClientCount("chrome/131", 1)), clients.kinds[1].platforms)
        assertEquals(
            listOf(LocalDate.parse("2031-03-14")),
            clients.history.map { it.day }.distinct(),
        )
        assertFailsWith<PermissionDeniedException> { service.clients(moderator.id) }
    }

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
