package dev.alllexey.itmowidgets.backend.feature.users.service

import dev.alllexey.itmowidgets.backend.feature.social.service.FriendService
import dev.alllexey.itmowidgets.backend.feature.users.model.FacultyEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.GroupEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.QualificationEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.testing.TestUsers
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserPrivacyServiceTest {
    private val friends = mock(FriendService::class.java)
    private val privacy = UserPrivacyService(friends)

    @ParameterizedTest
    @EnumSource(SharingVisibility::class)
    fun `both audiences depend only on owner and mutual friendship never viewer settings`(ownerVisibility: SharingVisibility) {
        for (viewerVisibility in SharingVisibility.entries) {
            for (friend in listOf(false, true)) {
                val viewer = TestUsers.user(100001, scheduleVisibility = viewerVisibility, sportVisibility = viewerVisibility)
                val owner = TestUsers.user(200002, scheduleVisibility = ownerVisibility, sportVisibility = ownerVisibility)
                `when`(friends.areFriends(viewer.isu, owner.isu)).thenReturn(friend)
                val expected = ownerVisibility == SharingVisibility.ALL ||
                    (ownerVisibility == SharingVisibility.FRIENDS && friend)
                assertEquals(expected, privacy.canViewSchedule(viewer, owner), "$ownerVisibility/$viewerVisibility/$friend")
                assertEquals(expected, privacy.canViewSport(viewer, owner), "$ownerVisibility/$viewerVisibility/$friend")
            }
        }
    }

    @ParameterizedTest
    @EnumSource(SharingVisibility::class)
    fun `self can read either type even when sharing with nobody`(visibility: SharingVisibility) {
        val owner = TestUsers.user(100001, scheduleVisibility = visibility, sportVisibility = visibility)
        assertTrue(privacy.canViewSchedule(owner, owner))
        assertTrue(privacy.canViewSport(owner, owner))
    }

    @Test
    fun `public access is viewer scoped and independent by data type`() {
        val owner = TestUsers.user(
            200002,
            scheduleVisibility = SharingVisibility.FRIENDS,
            sportVisibility = SharingVisibility.FRIENDS,
        ).apply {
            settings.sportVisibility = SharingVisibility.ALL
        }
        val friend = TestUsers.user(100001, scheduleVisibility = SharingVisibility.NOBODY, sportVisibility = SharingVisibility.NOBODY)
        val stranger = TestUsers.user(300003, scheduleVisibility = SharingVisibility.NOBODY, sportVisibility = SharingVisibility.NOBODY)
        `when`(friends.areFriends(friend.isu, owner.isu)).thenReturn(true)
        assertTrue(privacy.userDataFor(friend, owner).capabilities.canViewSchedule)
        assertFalse(privacy.userDataFor(stranger, owner).capabilities.canViewSchedule)
        assertTrue(privacy.userDataFor(stranger, owner).capabilities.canViewSport)
        assertEquals(SharingVisibility.FRIENDS, owner.settings.scheduleVisibility)
        assertEquals(SharingVisibility.ALL, owner.settings.sportVisibility)
    }

    @Test
    fun `stored groups are returned highest course first then by name`() {
        val owner = TestUsers.user(200002, scheduleVisibility = SharingVisibility.ALL, sportVisibility = SharingVisibility.ALL).apply {
            groups.addAll(listOf(group("P3119", 1), group("Z3244", 2), group("P3219", 2)))
        }
        assertEquals(listOf("P3219", "Z3244", "P3119"), privacy.userDataFor(owner, owner).groups.map { it.name })
    }

    private fun group(name: String, course: Int) = GroupEntity(
        id = UUID.nameUUIDFromBytes(name.toByteArray()),
        name = name,
        course = course,
        qualification = QualificationEntity(1, "Synthetic"),
        faculty = FacultyEntity(1, "Synthetic faculty", "SYN"),
    )
}
