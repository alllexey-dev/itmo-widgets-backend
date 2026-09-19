package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.RelationshipState
import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.repositories.LessonRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class LessonContextServiceTest {
    private val lessons = mock(LessonRepository::class.java)
    private val users = mock(UserRepository::class.java)
    private val friends = mock(FriendService::class.java)
    private val privacy = UserPrivacyService(friends)
    private val service = LessonContextService(lessons, users, friends, privacy)

    private val viewer = user(100001, SharingVisibility.NOBODY)
    private val date = LocalDate.parse("2026-09-08")

    @Test
    fun `only accepted friends with an open schedule audience are listed in friend order`() {
        val recentFriend = user(200002, SharingVisibility.FRIENDS)
        val olderFriend = user(300003, SharingVisibility.ALL)
        val hiddenFriend = user(400004, SharingVisibility.NOBODY)
        val stranger = user(500005, SharingVisibility.ALL)
        val absentFriend = user(600006, SharingVisibility.ALL)
        `when`(lessons.findAllUsersByPairIdAndDate(50, date))
            .thenReturn(listOf(stranger.isu, viewer.isu, olderFriend.isu, hiddenFriend.isu, recentFriend.isu))
        `when`(friends.getFriends(viewer.isu))
            .thenReturn(listOf(recentFriend.isu, olderFriend.isu, hiddenFriend.isu, absentFriend.isu))
        for (friend in listOf(recentFriend, olderFriend, hiddenFriend, absentFriend)) {
            `when`(friends.areFriends(viewer.isu, friend.isu)).thenReturn(true)
        }
        `when`(users.findAllByIsuIn(listOf(recentFriend.isu, olderFriend.isu, hiddenFriend.isu)))
            .thenReturn(listOf(hiddenFriend, olderFriend, recentFriend))

        val result = service.friendsOnLesson(viewer, 50, date)

        assertEquals(listOf(recentFriend.isu, olderFriend.isu), result.map { it.user.isu })
        assertTrue(result.all { it.relationship == RelationshipState.FRIENDS })
        assertTrue(result.all { it.user.capabilities.canViewSchedule })
        assertEquals("Synthetic user", result.first().user.name)
    }

    @Test
    fun `a lesson nobody else attends asks for no friends`() {
        `when`(lessons.findAllUsersByPairIdAndDate(50, date)).thenReturn(listOf(viewer.isu))

        assertTrue(service.friendsOnLesson(viewer, 50, date).isEmpty())

        verify(friends, never()).getFriends(viewer.isu)
        verify(users, never()).findAllByIsuIn(anyCollection())
    }

    @Test
    fun `attendees who are not friends never reach the user table`() {
        `when`(lessons.findAllUsersByPairIdAndDate(50, date)).thenReturn(listOf(500005, 500006))
        `when`(friends.getFriends(viewer.isu)).thenReturn(listOf(200002))

        assertTrue(service.friendsOnLesson(viewer, 50, date).isEmpty())

        verify(users, never()).findAllByIsuIn(anyCollection())
    }

    @Test
    fun `a friend without a user row is skipped and an unpublished name is empty`() {
        val unpublished = User(isu = 200002, name = null, pictureUrl = null).apply {
            settings = UserSettingsEntity(user = this, scheduleVisibility = SharingVisibility.ALL)
        }
        `when`(lessons.findAllUsersByPairIdAndDate(50, date)).thenReturn(listOf(200002, 300003))
        `when`(friends.getFriends(viewer.isu)).thenReturn(listOf(300003, 200002))
        `when`(friends.areFriends(viewer.isu, 200002)).thenReturn(true)
        `when`(users.findAllByIsuIn(listOf(300003, 200002))).thenReturn(listOf(unpublished))

        val result = service.friendsOnLesson(viewer, 50, date)

        assertEquals(listOf(200002), result.map { it.user.isu })
        assertEquals("", result.single().user.name)
    }

    private fun user(isu: Int, visibility: SharingVisibility) = User(isu = isu, name = "Synthetic user", pictureUrl = null).apply {
        settings = UserSettingsEntity(user = this, scheduleVisibility = visibility, sportVisibility = visibility)
    }
}
