package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.UserPrivacySettings
import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity.Companion.toDto
import dev.alllexey.itmowidgets.backend.repositories.GroupRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.core.model.UserSettings
import jakarta.persistence.EntityManager
import java.util.UUID
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class UserPrivacySettingsTest {
    private val users = mock(UserRepository::class.java)
    private val service = UserService(users, mock(ItmoJwtVerifier::class.java),
        mock(GroupService::class.java), mock(EntityManager::class.java), mock(GroupRepository::class.java))
    private val user = User(isu = 100001, pictureUrl = null, name = "Synthetic user").apply {
        settings = UserSettingsEntity(UUID.randomUUID())
    }

    @BeforeEach
    fun managedUserLookup() {
        `when`(users.findById(user.id)).thenReturn(Optional.of(user))
    }

    @Test
    fun `legacy rows retain true friends and false nobody without a widening backfill`() {
        user.settings.scheduleSharing = true
        assertEquals(UserPrivacySettings(SharingVisibility.FRIENDS, SharingVisibility.NOBODY), service.privacySettings(user))
        assertEquals(UserSettings(false, true), user.settings.toDto())
    }

    @Test
    fun `enum update changes both independent settings and compatibility mirrors`() {
        val requested = UserPrivacySettings(SharingVisibility.ALL, SharingVisibility.NOBODY)
        assertEquals(requested, service.updatePrivacySettings(user, requested))
        assertEquals(SharingVisibility.ALL, user.settings.scheduleVisibility)
        assertEquals(SharingVisibility.NOBODY, user.settings.sportVisibility)
        assertTrue(user.settings.scheduleSharing)
        assertFalse(user.settings.sportSharing)
        assertEquals(UserSettings(false, true), user.settings.toDto())
    }

    @Test
    fun `legacy true preserves all while false closes visibility independently`() {
        service.updatePrivacySettings(user, UserPrivacySettings(SharingVisibility.ALL, SharingVisibility.ALL))
        service.updateSettings(user, UserSettings(sportSharing = false, scheduleSharing = true))
        assertEquals(UserPrivacySettings(SharingVisibility.ALL, SharingVisibility.NOBODY), service.privacySettings(user))
        service.updateSettings(user, UserSettings(sportSharing = true, scheduleSharing = false))
        assertEquals(UserPrivacySettings(SharingVisibility.NOBODY, SharingVisibility.FRIENDS), service.privacySettings(user))
    }

    @Test
    fun `legacy true on old row enables friends not all`() {
        service.updateSettings(user, UserSettings(sportSharing = true, scheduleSharing = true))
        assertEquals(UserPrivacySettings(SharingVisibility.FRIENDS, SharingVisibility.FRIENDS), service.privacySettings(user))
    }
}
