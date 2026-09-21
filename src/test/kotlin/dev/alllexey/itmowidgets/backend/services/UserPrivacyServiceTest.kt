package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.FacultyEntity
import dev.alllexey.itmowidgets.backend.model.GroupEntity
import dev.alllexey.itmowidgets.backend.model.QualificationEntity
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
    fun `public access is viewer scoped and independent by data type`() {
        val owner = user(200002, SharingVisibility.FRIENDS).apply {
            settings.sportVisibility = SharingVisibility.ALL
        }
        val friend = user(100001, SharingVisibility.NOBODY)
        val stranger = user(300003, SharingVisibility.NOBODY)
        `when`(friends.areFriends(friend.isu, owner.isu)).thenReturn(true)
        assertTrue(privacy.userDataFor(friend, owner).capabilities.canViewSchedule)
        assertFalse(privacy.userDataFor(stranger, owner).capabilities.canViewSchedule)
        assertTrue(privacy.userDataFor(stranger, owner).capabilities.canViewSport)
        assertEquals(SharingVisibility.FRIENDS, owner.settings.scheduleVisibility)
        assertEquals(SharingVisibility.ALL, owner.settings.sportVisibility)
    }

    @Test
    fun `stored groups are returned highest course first then by name`() {
        val owner = user(200002, SharingVisibility.ALL).apply {
            groups.addAll(listOf(group("P3119", 1), group("Z3244", 2), group("P3219", 2)))
        }
        assertEquals(listOf("P3219", "Z3244", "P3119"), privacy.userDataFor(owner, owner).groups.map { it.name })
    }

    private fun group(name: String, course: Int) = GroupEntity(
        id = UUID.nameUUIDFromBytes(name.toByteArray()), name = name, course = course,
        qualification = QualificationEntity(1, "Synthetic"), faculty = FacultyEntity(1, "Synthetic faculty", "SYN"),
    )

    private fun user(isu: Int, visibility: SharingVisibility) = User(isu = isu, name = "Synthetic user", pictureUrl = null).apply {
        settings = UserSettingsEntity(user = this, scheduleVisibility = visibility, sportVisibility = visibility)
    }
}
