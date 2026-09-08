package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class UserPrivacyServiceTest {
    private val friends = mock(FriendService::class.java)
    private val privacy = UserPrivacyService(friends)

    @ParameterizedTest
    @EnumSource(SharingVisibility::class)
    fun `both audiences depend only on owner and mutual friendship never viewer settings`(ownerVisibility: SharingVisibility) {
        for (viewerVisibility in SharingVisibility.entries) for (friend in listOf(false, true)) {
            val viewer = user(100001, viewerVisibility)
            val owner = user(200002, ownerVisibility)
            `when`(friends.areFriends(viewer.isu, owner.isu)).thenReturn(friend)
            val expected = ownerVisibility == SharingVisibility.ALL ||
                (ownerVisibility == SharingVisibility.FRIENDS && friend)
            assertEquals(expected, privacy.canViewSchedule(viewer, owner), "$ownerVisibility/$viewerVisibility/$friend")
            assertEquals(expected, privacy.canViewSport(viewer, owner), "$ownerVisibility/$viewerVisibility/$friend")
        }
    }

    @ParameterizedTest
    @EnumSource(SharingVisibility::class)
    fun `self can read either type even when sharing with nobody`(visibility: SharingVisibility) {
        val owner = user(100001, visibility)
        assertTrue(privacy.canViewSchedule(owner, owner))
        assertTrue(privacy.canViewSport(owner, owner))
    }

    @Test
    fun `public legacy settings are viewer scoped capabilities and independent by data type`() {
        val owner = user(200002, SharingVisibility.FRIENDS).apply {
            settings.sportVisibility = SharingVisibility.ALL
        }
        val friend = user(100001, SharingVisibility.NOBODY)
        val stranger = user(300003, SharingVisibility.NOBODY)
        `when`(friends.areFriends(friend.isu, owner.isu)).thenReturn(true)
        assertTrue(privacy.userDataFor(friend, owner).settings.scheduleSharing)
        assertFalse(privacy.userDataFor(stranger, owner).settings.scheduleSharing)
        assertTrue(privacy.userDataFor(stranger, owner).settings.sportSharing)
        assertEquals(SharingVisibility.FRIENDS, owner.settings.scheduleVisibility)
        assertEquals(SharingVisibility.ALL, owner.settings.sportVisibility)
    }

    private fun user(isu: Int, visibility: SharingVisibility) = User(isu = isu, name = "Synthetic user", pictureUrl = null).apply {
        settings = UserSettingsEntity(UUID.randomUUID(), scheduleVisibility = visibility, sportVisibility = visibility)
    }
}
