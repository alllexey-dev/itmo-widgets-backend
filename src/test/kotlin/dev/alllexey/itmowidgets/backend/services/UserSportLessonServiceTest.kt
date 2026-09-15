package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.backend.repositories.UserSportLessonRepository
import dev.alllexey.itmowidgets.core.model.*
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
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
        val viewer = user(100000, sportVisibility = SharingVisibility.NOBODY)
        val visibleFriend = user(200000, sportVisibility = SharingVisibility.FRIENDS)
        val privateFriend = user(300000, sportVisibility = SharingVisibility.NOBODY)
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
        val viewer = user(100000, sportVisibility = SharingVisibility.NOBODY)
        val privateFriend = user(300000, sportVisibility = SharingVisibility.NOBODY)
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
        val owner = user(100000, sportVisibility = SharingVisibility.NOBODY)
        `when`(userRepository.lockById(owner.id)).thenReturn(owner.id)
        `when`(userService.findUserById(owner.id)).thenReturn(owner)
        service.syncLessons(owner.id, listOf(10L, 20L))
        val order = inOrder(userRepository, freeSignService, autoSignService, repo)
        order.verify(userRepository).lockById(owner.id)
        order.verify(freeSignService).sync(owner, listOf(10L, 20L))
        order.verify(autoSignService).sync(owner, listOf(10L, 20L))
        order.verify(repo).deleteMissingFutureLessons(owner.id, listOf(10L, 20L), clock.instant())
        order.verify(repo).insertLessonsIgnoreDuplicates(owner.id, listOf(10L, 20L), clock.instant())
        verify(repo).deleteMissingFutureLessons(owner.id, listOf(10L, 20L), clock.instant())
        verify(repo).insertLessonsIgnoreDuplicates(owner.id, listOf(10L, 20L), clock.instant())
        verify(freeSignService).sync(owner, listOf(10L, 20L))
        verify(autoSignService).sync(owner, listOf(10L, 20L))
    }

    @Test
    fun `empty confirmed sync removes missing future bookings and reconciles queues`() {
        val owner = user(100000, sportVisibility = SharingVisibility.NOBODY)
        `when`(userRepository.lockById(owner.id)).thenReturn(owner.id)
        `when`(userService.findUserById(owner.id)).thenReturn(owner)

        service.syncLessons(owner.id, emptyList())

        verify(repo).deleteMissingFutureLessons(owner.id, listOf(-1L), clock.instant())
        verify(repo).insertLessonsIgnoreDuplicates(owner.id, emptyList(), clock.instant())
        verify(freeSignService).sync(owner, emptyList())
        verify(autoSignService).sync(owner, emptyList())
    }

    @Test
    fun `target sport read returns confirmed ids and loads authorized queues`() {
        val owner = user(100000, sportVisibility = SharingVisibility.NOBODY)
        `when`(userService.findUserById(owner.id)).thenReturn(owner)
        `when`(userService.findUserByIsu(owner.isu)).thenReturn(owner)
        val lesson = mock(dev.alllexey.itmowidgets.backend.model.SportLesson::class.java)
        `when`(lesson.id).thenReturn(15L)
        val booking = dev.alllexey.itmowidgets.backend.model.UserSportLesson(user = owner, lesson = lesson)
        `when`(repo.findByUserIsuIn(listOf(owner.isu), OffsetDateTime.now(clock))).thenReturn(listOf(booking, booking))
        kotlin.test.assertEquals(listOf(15L), service.getUserBookings(owner.id, owner.isu).lessonIds)
        verify(freeSignService).getUserEntries(owner.id)
        verify(autoSignService).getUserEntries(owner.id)
    }

    @Test
    fun `target sport includes both active queue types and excludes cancelled and terminal entries`() {
        val owner = user(100000, SharingVisibility.ALL)
        val viewer = user(100001, SharingVisibility.NOBODY)
        `when`(userService.findUserById(viewer.id)).thenReturn(viewer)
        `when`(userService.findUserByIsu(owner.isu)).thenReturn(owner)
        val free = freeEntry()
        val auto = autoEntry()
        `when`(freeSignService.getUserEntries(owner.id)).thenReturn(listOf(
            free, free.copy(id = 3, isCancelled = true),
            free.copy(id = 4, status = QueueEntryStatus.SATISFIED),
            free.copy(id = 5, status = QueueEntryStatus.EXPIRED),
            free.copy(id = 6, status = QueueEntryStatus.GAVE_UP_NOTIFYING),
        ))
        `when`(autoSignService.getUserEntries(owner.id)).thenReturn(listOf(
            auto, auto.copy(id = 7, isCancelled = true),
            auto.copy(id = 8, status = QueueEntryStatus.SATISFIED),
            auto.copy(id = 9, status = QueueEntryStatus.EXPIRED),
            auto.copy(id = 10, status = QueueEntryStatus.GAVE_UP_NOTIFYING),
        ))
        val response = service.getUserBookings(viewer.id, owner.isu)
        assertEquals(emptyList(), response.lessonIds)
        assertEquals(listOf(free, auto), response.entries)
        assertEquals(auto.prototypeLessonId, (response.entries[1] as SportAutoSignEntry).prototypeLessonId)
        assertEquals(null, (response.entries[1] as SportAutoSignEntry).realLessonId)
    }

    @Test
    fun `foreign sport queues remain unavailable for pending relationships and private friends`() {
        val viewer = user(100001, SharingVisibility.ALL)
        `when`(userService.findUserById(viewer.id)).thenReturn(viewer)
        for (visibility in listOf(SharingVisibility.FRIENDS, SharingVisibility.NOBODY)) {
            val owner = user(100000, visibility)
            `when`(userService.findUserByIsu(owner.isu)).thenReturn(owner)
            `when`(friendService.areFriends(viewer.isu, owner.isu)).thenReturn(visibility == SharingVisibility.NOBODY)
            assertFailsWith<PermissionDeniedException> { service.getUserBookings(viewer.id, owner.isu) }
        }
        verifyNoInteractions(repo, freeSignService, autoSignService)
    }

    private fun targetLesson() = SportLessonDto(
        id = 50, sectionId = 1, sectionName = "Synthetic section", sectionLevel = 1, level = 1,
        typeId = 1, buildingId = 1, roomName = "Synthetic room", start = OffsetDateTime.now(clock).plusDays(1),
        end = OffsetDateTime.now(clock).plusDays(1).plusHours(1), timeSlotId = 1, teacherIsu = 123456,
        teacherFio = "Synthetic teacher",
    )

    private fun freeEntry() = SportFreeSignEntry(
        id = 1, lessonId = 50, position = 1, total = 1, isCancelled = false, status = QueueEntryStatus.WAITING,
        createdAt = OffsetDateTime.now(clock), firstNotifiedAt = null, lastNotifiedAt = null, cancelledAt = null,
        satisfiedAt = null, expiredAt = null, notificationAttempts = 0, maxNotificationAttempts = 10,
        targetLesson = targetLesson(), forceSign = false,
    )

    private fun autoEntry() = SportAutoSignEntry(
        id = 2, prototypeLessonId = 50, realLessonId = null, position = 1, total = 1,
        isCancelled = false, status = QueueEntryStatus.NOTIFIED, createdAt = OffsetDateTime.now(clock),
        firstNotifiedAt = OffsetDateTime.now(clock), lastNotifiedAt = OffsetDateTime.now(clock),
        cancelledAt = null, satisfiedAt = null, expiredAt = null, notificationAttempts = 1,
        maxNotificationAttempts = 10, targetLesson = targetLesson(), realLesson = null,
    )

    private fun user(isu: Int, sportVisibility: SharingVisibility): User = User(
        isu = isu,
        pictureUrl = null,
        name = null
    ).apply {
        settings = UserSettingsEntity(
            user = this,
            sportVisibility = sportVisibility
        )
    }
}
