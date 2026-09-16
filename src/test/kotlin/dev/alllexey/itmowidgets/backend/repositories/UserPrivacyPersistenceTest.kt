package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.dto.UserPrivacySettings
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.services.UserRegistrationService
import dev.alllexey.itmowidgets.backend.services.UserService
import dev.alllexey.itmowidgets.backend.services.GroupService
import dev.alllexey.itmowidgets.backend.services.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import java.util.UUID
import kotlin.test.assertNull
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean

@Import(UserService::class, UserRegistrationService::class)
class UserPrivacyPersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val em: TestEntityManager,
    private val service: UserService,
) : PostgreSqlRepositoryTest() {
    @MockitoBean private lateinit var itmoJwtVerifier: ItmoJwtVerifier
    @MockitoBean private lateinit var groupService: GroupService

    @Test
    fun `privacy update persists audiences even when passed user is detached`() {
        val owner = User(isu = 100001, pictureUrl = null, name = "Synthetic user").apply {
            settings = UserSettingsEntity(user = this)
        }
        em.persistAndFlush(owner)
        em.clear()
        val requested = UserPrivacySettings(SharingVisibility.ALL, SharingVisibility.FRIENDS, SharingVisibility.NOBODY)
        assertEquals(requested, service.updatePrivacySettings(owner, requested))
        em.flush()
        em.clear()
        val persisted = em.find(User::class.java, owner.id).settings
        assertEquals(SharingVisibility.ALL, persisted.scheduleVisibility)
        assertEquals(SharingVisibility.FRIENDS, persisted.sportVisibility)
        assertEquals(SharingVisibility.NOBODY, persisted.friendsVisibility)
        assertEquals(owner.id, persisted.userId)
    }

    @Test
    fun `native new user settings default to friends`() {
        val id = UUID.randomUUID()
        assertEquals(1, users.insertIgnore(id, 100002))
        assertEquals(1, users.insertSettingsIgnore(id))
        val settings = em.find(UserSettingsEntity::class.java, id)
        assertEquals(SharingVisibility.FRIENDS, settings.scheduleVisibility)
        assertEquals(SharingVisibility.FRIENDS, settings.sportVisibility)
        assertEquals(SharingVisibility.ALL, settings.friendsVisibility)
        assertEquals(id, settings.userId)
        assertEquals(0, users.insertSettingsIgnore(id))
    }

    @Test
    fun `explicit audiences and quota round trip without altering choices`() {
        val owner = User(isu = 100003, pictureUrl = null, name = "Synthetic user").apply {
            settings = UserSettingsEntity(user = this, autoSignLimit = 7,
                sportVisibility = SharingVisibility.NOBODY, scheduleVisibility = SharingVisibility.ALL)
        }
        em.persistAndFlush(owner)
        em.clear()
        val settings = em.find(UserSettingsEntity::class.java, owner.id)
        assertEquals(SharingVisibility.ALL, settings.scheduleVisibility)
        assertEquals(SharingVisibility.NOBODY, settings.sportVisibility)
        assertEquals(7, settings.autoSignLimit)
        assertEquals(owner.id, settings.userId)
    }

    @Test
    fun `JPA creates shared settings with friends defaults and removes them with their user`() {
        val owner = User(isu = 100004, pictureUrl = null, name = "Synthetic user").apply {
            settings = UserSettingsEntity(user = this)
        }
        em.persistAndFlush(owner)
        em.clear()
        val persisted = em.find(User::class.java, owner.id)
        assertEquals(owner.id, persisted.settings.userId)
        assertEquals(owner.id, persisted.settings.user.id)
        assertEquals(SharingVisibility.FRIENDS, persisted.settings.scheduleVisibility)
        assertEquals(SharingVisibility.FRIENDS, persisted.settings.sportVisibility)

        em.remove(persisted)
        em.flush()
        em.clear()

        assertNull(em.find(User::class.java, owner.id))
        assertNull(em.find(UserSettingsEntity::class.java, owner.id))
    }

}
