package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.dto.UserPrivacySettings
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.services.UserService
import dev.alllexey.itmowidgets.backend.services.GroupService
import dev.alllexey.itmowidgets.backend.services.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ContextConfiguration

@DataJpaTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:user_privacy;MODE=MariaDB;NON_KEYWORDS=GROUPS,START,END",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop"
], showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [UserPrivacyPersistenceTest.PersistenceConfig::class])
@Import(UserService::class)
class UserPrivacyPersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val em: TestEntityManager,
    private val service: UserService,
) {
    @MockitoBean private lateinit var itmoJwtVerifier: ItmoJwtVerifier
    @MockitoBean private lateinit var groupService: GroupService

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = [UserSettingsEntity::class])
    @EnableJpaRepositories(basePackageClasses = [UserRepository::class])
    class PersistenceConfig

    @Test
    fun `privacy update persists enum and legacy mirrors even when passed user is detached`() {
        val owner = User(isu = 100001, pictureUrl = null, name = "Synthetic user").apply {
            settings = UserSettingsEntity(UUID.randomUUID())
        }
        em.persistAndFlush(owner)
        em.clear()
        val requested = UserPrivacySettings(SharingVisibility.ALL, SharingVisibility.FRIENDS)
        assertEquals(requested, service.updatePrivacySettings(owner, requested))
        em.flush()
        em.clear()
        val persisted = em.find(User::class.java, owner.id).settings
        assertEquals(SharingVisibility.ALL, persisted.scheduleVisibility)
        assertEquals(SharingVisibility.FRIENDS, persisted.sportVisibility)
        assertTrue(persisted.scheduleSharing)
        assertTrue(persisted.sportSharing)
    }

    @Test
    fun `native new user settings explicitly default to friends and true legacy mirrors`() {
        val id = UUID.randomUUID()
        assertEquals(1, users.insertSettingsIgnore(id))
        val settings = em.find(UserSettingsEntity::class.java, id)
        assertEquals(SharingVisibility.FRIENDS, settings.scheduleVisibility)
        assertEquals(SharingVisibility.FRIENDS, settings.sportVisibility)
        assertTrue(settings.scheduleSharing)
        assertTrue(settings.sportSharing)
        assertEquals(0, users.insertSettingsIgnore(id))
    }

    @Test
    fun `nullable legacy values round trip without altering old privacy choices`() {
        val id = UUID.randomUUID()
        em.persistAndFlush(UserSettingsEntity(id, autoSignLimit = 7, sportSharing = false, scheduleSharing = true))
        em.clear()
        val settings = em.find(UserSettingsEntity::class.java, id)
        assertNull(settings.scheduleVisibility)
        assertNull(settings.sportVisibility)
        assertEquals(SharingVisibility.FRIENDS, settings.effectiveScheduleVisibility())
        assertEquals(SharingVisibility.NOBODY, settings.effectiveSportVisibility())
        assertEquals(7, settings.autoSignLimit)
        assertFalse(settings.sportSharing)
    }
}
