package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.backend.repositories.UserSportLessonRepository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class UserSportLessonServiceTest {

    private val repo = mock(UserSportLessonRepository::class.java)
    private val userService = mock(UserService::class.java)
    private val freeSignService = mock(SportFreeSignService::class.java)
    private val autoSignService = mock(SportAutoSignService::class.java)
    private val friendService = mock(FriendService::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val clock = Clock.fixed(
        Instant.parse("2026-09-07T09:00:00Z"),
        ZoneOffset.UTC
    )
    private val service = UserSportLessonService(
        repo,
        userService,
        freeSignService,
        autoSignService,
        friendService,
        userRepository,
        clock,
        UserPrivacyService(friendService)
    )

    @Test
    fun `loads bookings only for friends who share sport activity`() {
        val viewer = user(100000, sportSharing = false)
        val visibleFriend = user(200000, sportSharing = true)
        val privateFriend = user(300000, sportSharing = false)
        `when`(friendService.areFriends(viewer.isu, visibleFriend.isu)).thenReturn(true)
        val allFriendIsus = listOf(visibleFriend.isu, privateFriend.isu)
        `when`(userService.findUserById(viewer.id)).thenReturn(viewer)
        `when`(friendService.getFriends(viewer.isu)).thenReturn(allFriendIsus)
        `when`(userRepository.findAllByIsuIn(allFriendIsus))
            .thenReturn(listOf(visibleFriend, privateFriend))
        `when`(
            repo.findByUserIsuIn(
                listOf(visibleFriend.isu),
                OffsetDateTime.now(clock)
            )
        ).thenReturn(emptyList())
        `when`(freeSignService.getUserEntries(visibleFriend.id)).thenReturn(emptyList())
        `when`(autoSignService.getUserEntries(visibleFriend.id)).thenReturn(emptyList())

        service.getUserFriendsBookings(viewer.id)

        verify(repo).findByUserIsuIn(
            listOf(visibleFriend.isu),
            OffsetDateTime.now(clock)
        )
        verify(freeSignService).getUserEntries(visibleFriend.id)
        verify(autoSignService).getUserEntries(visibleFriend.id)
        verify(freeSignService, never()).getUserEntries(privateFriend.id)
        verify(autoSignService, never()).getUserEntries(privateFriend.id)
    }

    @Test
    fun `returns empty bookings when no friend shares sport activity`() {
        val viewer = user(100000, sportSharing = false)
        val privateFriend = user(300000, sportSharing = false)
        `when`(userService.findUserById(viewer.id)).thenReturn(viewer)
        `when`(friendService.getFriends(viewer.isu)).thenReturn(listOf(privateFriend.isu))
        `when`(userRepository.findAllByIsuIn(listOf(privateFriend.isu)))
            .thenReturn(listOf(privateFriend))

        val result = service.getUserFriendsBookings(viewer.id)

        kotlin.test.assertTrue(result.bookings.isEmpty())
        verifyNoInteractions(repo, freeSignService, autoSignService)
    }

    @Test
    fun `private confirmed sync still updates self data and queue reconciliation`() {
        val owner = user(100000, sportSharing = false)
        `when`(userService.findUserById(owner.id)).thenReturn(owner)
        service.syncLessons(owner.id, listOf(10L, 20L))
        verify(repo).deleteMissingFutureLessons(owner.id, listOf(10L, 20L))
        verify(repo).insertLessonsIgnoreDuplicates(owner.id, listOf(10L, 20L))
        verify(freeSignService).sync(owner, listOf(10L, 20L))
        verify(autoSignService).sync(owner, listOf(10L, 20L))
    }

    @Test
    fun `empty confirmed sync removes missing future bookings and reconciles queues`() {
        val owner = user(100000, sportSharing = false)
        `when`(userService.findUserById(owner.id)).thenReturn(owner)

        service.syncLessons(owner.id, emptyList())

        verify(repo).deleteMissingFutureLessons(owner.id, listOf(-1L))
        verify(repo).insertLessonsIgnoreDuplicates(owner.id, emptyList())
        verify(freeSignService).sync(owner, emptyList())
        verify(autoSignService).sync(owner, emptyList())
    }

    @Test
    fun `target sport read returns confirmed ids only never invokes queues`() {
        val owner = user(100000, sportSharing = false)
        `when`(userService.findUserById(owner.id)).thenReturn(owner)
        `when`(userService.findUserByIsu(owner.isu)).thenReturn(owner)
        val lesson = mock(dev.alllexey.itmowidgets.backend.model.SportLesson::class.java)
        `when`(lesson.id).thenReturn(15L)
        val booking = dev.alllexey.itmowidgets.backend.model.UserSportLesson(user = owner, lesson = lesson)
        `when`(repo.findByUserIsuIn(listOf(owner.isu), OffsetDateTime.now(clock))).thenReturn(listOf(booking, booking))
        kotlin.test.assertEquals(listOf(15L), service.getUserBookings(owner.id, owner.isu).lessonIds)
        verifyNoInteractions(freeSignService, autoSignService)
    }

    private fun user(isu: Int, sportSharing: Boolean): User = User(
        isu = isu,
        pictureUrl = null,
        name = null
    ).apply {
        settings = UserSettingsEntity(
            id = UUID.randomUUID(),
            sportSharing = sportSharing
        )
    }
}
