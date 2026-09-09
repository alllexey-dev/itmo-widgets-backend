package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.UserPrivacySettings
import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.repositories.GroupRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import jakarta.persistence.EntityManager
import java.util.Optional
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class UserPrivacySettingsTest {
    private val users = mock(UserRepository::class.java)
    private val service = UserService(users, mock(ItmoJwtVerifier::class.java),
        mock(GroupService::class.java), mock(EntityManager::class.java), mock(GroupRepository::class.java),
        mock(UserRegistrationService::class.java))
    private val user = User(isu = 100001, pictureUrl = null, name = "Synthetic user").apply {
        settings = UserSettingsEntity(user = this)
    }

    @BeforeEach
    fun managedUserLookup() {
        `when`(users.findById(user.id)).thenReturn(Optional.of(user))
    }

    @Test
    fun `new settings default both audiences to friends`() {
        assertEquals(UserPrivacySettings(SharingVisibility.FRIENDS, SharingVisibility.FRIENDS), service.privacySettings(user))
    }

    @Test
    fun `enum update changes both independent settings`() {
        val requested = UserPrivacySettings(SharingVisibility.ALL, SharingVisibility.NOBODY)
        assertEquals(requested, service.updatePrivacySettings(user, requested))
        assertEquals(SharingVisibility.ALL, user.settings.scheduleVisibility)
        assertEquals(SharingVisibility.NOBODY, user.settings.sportVisibility)
    }

    @Test
    fun `schedule and sport choices can be reversed independently`() {
        service.updatePrivacySettings(user, UserPrivacySettings(SharingVisibility.ALL, SharingVisibility.NOBODY))
        val requested = UserPrivacySettings(SharingVisibility.NOBODY, SharingVisibility.FRIENDS)
        service.updatePrivacySettings(user, requested)
        assertEquals(requested, service.privacySettings(user))
    }

    @Test
    fun `updating privacy does not modify auto sign limit or settings identity`() {
        user.settings.autoSignLimit = 7
        service.updatePrivacySettings(user, UserPrivacySettings(SharingVisibility.ALL, SharingVisibility.ALL))
        assertEquals(7, user.settings.autoSignLimit)
        assertEquals(user.id, user.settings.userId)
    }
}
