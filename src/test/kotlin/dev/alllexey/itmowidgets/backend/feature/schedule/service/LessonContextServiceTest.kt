package dev.alllexey.itmowidgets.backend.feature.schedule.service

import dev.alllexey.itmowidgets.backend.feature.schedule.persistence.LessonRepository
import dev.alllexey.itmowidgets.backend.feature.social.service.FriendService
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.web.RelationshipState
import dev.alllexey.itmowidgets.backend.testing.TestClock
import dev.alllexey.itmowidgets.backend.testing.TestUsers
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LessonContextServiceTest {
    private val lessons = mock(LessonRepository::class.java)
    private val users = mock(UserRepository::class.java)
    private val friends = mock(FriendService::class.java)
    private val privacy = UserPrivacyService(friends)
    private val service = LessonContextService(lessons, users, friends, privacy, TestClock.CLOCK)

    private val viewer = TestUsers.user(100001, scheduleVisibility = SharingVisibility.NOBODY, sportVisibility = SharingVisibility.NOBODY)
    private val date = LocalDate.parse("2026-09-08")

    @Test
    fun `only accepted friends with an open schedule audience are listed in friend order`() {
        val recentFriend = TestUsers.user(
            200002,
            scheduleVisibility = SharingVisibility.FRIENDS,
            sportVisibility = SharingVisibility.FRIENDS,
        )
        val olderFriend = TestUsers.user(300003, scheduleVisibility = SharingVisibility.ALL, sportVisibility = SharingVisibility.ALL)
        val hiddenFriend = TestUsers.user(400004, scheduleVisibility = SharingVisibility.NOBODY, sportVisibility = SharingVisibility.NOBODY)
        val stranger = TestUsers.user(500005, scheduleVisibility = SharingVisibility.ALL, sportVisibility = SharingVisibility.ALL)
        val absentFriend = TestUsers.user(600006, scheduleVisibility = SharingVisibility.ALL, sportVisibility = SharingVisibility.ALL)
        `when`(lessons.findAllUsersByPairIdAndDate(50, date))
            .thenReturn(listOf(stranger.isu, viewer.isu, olderFriend.isu, hiddenFriend.isu, recentFriend.isu))
        `when`(friends.getFriends(viewer.isu))
            .thenReturn(listOf(recentFriend.isu, olderFriend.isu, hiddenFriend.isu, absentFriend.isu))
        `when`(users.findAllByIsuIn(listOf(recentFriend.isu, olderFriend.isu, hiddenFriend.isu)))
            .thenReturn(listOf(hiddenFriend, olderFriend, recentFriend))

        val result = service.friendsOnLesson(viewer, 50, date)

        assertEquals(listOf(recentFriend.isu, olderFriend.isu), result.map { it.user.isu })
        assertTrue(result.all { it.relationship == RelationshipState.FRIENDS })
        assertTrue(result.all { it.user.capabilities.canViewSchedule })
        assertEquals("Synthetic user", result.first().user.name)
        verify(friends, never()).areFriends(anyInt(), anyInt())
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
        val unpublished = TestUsers.user(200002, name = null, scheduleVisibility = SharingVisibility.ALL)
        `when`(lessons.findAllUsersByPairIdAndDate(50, date)).thenReturn(listOf(200002, 300003))
        `when`(friends.getFriends(viewer.isu)).thenReturn(listOf(300003, 200002))
        `when`(users.findAllByIsuIn(listOf(300003, 200002))).thenReturn(listOf(unpublished))

        val result = service.friendsOnLesson(viewer, 50, date)

        assertEquals(listOf(200002), result.map { it.user.isu })
        assertEquals("", result.single().user.name)
    }

    @Test
    fun `a day without friends reads neither users nor lessons`() {
        `when`(friends.getFriends(viewer.isu)).thenReturn(emptyList())

        assertTrue(service.friendsOnDay(viewer, date).isEmpty())

        verifyNoInteractions(users, lessons)
    }

    @Test
    fun `a day reads lessons only of friends whose audience admits the viewer`() {
        val open = TestUsers.user(200002, scheduleVisibility = SharingVisibility.FRIENDS)
        val hidden = TestUsers.user(300003, scheduleVisibility = SharingVisibility.NOBODY)
        `when`(friends.getFriends(viewer.isu)).thenReturn(listOf(hidden.isu, open.isu))
        `when`(users.findAllByIsuIn(listOf(hidden.isu, open.isu))).thenReturn(listOf(open, hidden))
        `when`(lessons.findAllByIsuInAndDate(listOf(open.isu), date)).thenReturn(emptyList())

        assertTrue(service.friendsOnDay(viewer, date).isEmpty())

        verify(lessons).findAllByIsuInAndDate(listOf(open.isu), date)
        verify(friends, never()).areFriends(anyInt(), anyInt())
    }

    @Test
    fun `hidden friends alone read no lessons`() {
        val hidden = TestUsers.user(300003, scheduleVisibility = SharingVisibility.NOBODY)
        `when`(friends.getFriends(viewer.isu)).thenReturn(listOf(hidden.isu))
        `when`(users.findAllByIsuIn(listOf(hidden.isu))).thenReturn(listOf(hidden))

        assertTrue(service.friendsOnDay(viewer, date).isEmpty())

        verify(lessons, never()).findAllByIsuInAndDate(anyCollection(), any() ?: date)
    }
}
