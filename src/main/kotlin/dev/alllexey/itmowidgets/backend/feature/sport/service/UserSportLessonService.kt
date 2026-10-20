package dev.alllexey.itmowidgets.backend.feature.sport.service

import dev.alllexey.itmowidgets.backend.feature.social.service.FriendService
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.UserSportLessonRepository
import dev.alllexey.itmowidgets.backend.feature.sport.service.SportQueueTransitionService.Companion.notifiableStatuses
import dev.alllexey.itmowidgets.backend.feature.sport.web.FriendSportBooking
import dev.alllexey.itmowidgets.backend.feature.sport.web.FriendsSportBookingsResponse
import dev.alllexey.itmowidgets.backend.feature.sport.web.UserSportBookingsResponse
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@Service
class UserSportLessonService(
    private val repo: UserSportLessonRepository,
    private val userService: UserService,
    private val sportFreeSignService: SportFreeSignService,
    private val sportAutoSignService: SportAutoSignService,
    private val friendService: FriendService,
    private val userRepository: UserRepository,
    private val clock: Clock,
    private val privacyService: UserPrivacyService,
) {

    /** The caller's own sign-ups from MyITMO; more than [MAX_SYNCED] ids are refused. */
    @Transactional
    fun syncLessons(userId: UUID, lessonIds: List<Long>) {
        if (lessonIds.size > MAX_SYNCED) throw InvalidRequestDataException("More than $MAX_SYNCED sport lessons")
        userRepository.lockById(userId) ?: throw NotFoundException("User not found")
        val now = Instant.now(clock)
        val user = userService.findUserById(userId)
        sportFreeSignService.sync(user, lessonIds)
        sportAutoSignService.sync(user, lessonIds)
        // Storage supports self reads regardless of visibility; access is enforced on every read.
        repo.deleteMissingFutureLessons(userId, lessonIds.ifEmpty { listOf(-1L) }, now)
        repo.insertLessonsIgnoreDuplicates(userId, lessonIds, now)
    }

    @Transactional(readOnly = true)
    fun getUserBookings(viewerId: UUID, ownerIsu: Int): UserSportBookingsResponse {
        val viewer = userService.findUserById(viewerId)
        val owner = userService.findUserByIsu(ownerIsu)
        if (!privacyService.canViewSport(viewer, owner)) {
            throw PermissionDeniedException("Sport activity is not shared with this user")
        }
        val lessonIds = repo.findByUserIsuIn(listOf(owner.isu), OffsetDateTime.now(clock))
            .map { it.lesson.id }.distinct().sorted()
        val entries = (sportFreeSignService.getUserEntries(owner.id) + sportAutoSignService.getUserEntries(owner.id))
            .filter { !it.isCancelled && it.status in notifiableStatuses }
        return UserSportBookingsResponse(lessonIds, entries)
    }

    @Transactional(readOnly = true)
    fun getUserFriendsBookings(userId: UUID): FriendsSportBookingsResponse {
        val user = userService.findUserById(userId)
        val friendIsus = friendService.getFriends(user.isu)
        val visibleFriends = userRepository.findAllByIsuIn(friendIsus)
            .filter { privacyService.canViewSport(user, it) }
        if (visibleFriends.isEmpty()) {
            return FriendsSportBookingsResponse(emptyList())
        }

        val visibleFriendIsus = visibleFriends.map { it.isu }
        val signedLessons = repo.findByUserIsuIn(
            visibleFriendIsus,
            OffsetDateTime.now(clock),
        ).map { lesson ->
            FriendSportBooking(
                isu = lesson.user.isu,
                lessonId = lesson.lesson.id,
                entry = null,
            )
        }

        val freeSignEntries = visibleFriends.flatMap { friend ->
            sportFreeSignService.getUserEntries(friend.id)
                .filter { it.status in notifiableStatuses }
                .map { entry ->
                    FriendSportBooking(
                        isu = friend.isu,
                        lessonId = entry.lessonId,
                        entry = entry,
                    )
                }
        }

        val autoSignEntries = visibleFriends.flatMap { friend ->
            sportAutoSignService.getUserEntries(friend.id)
                .filter { it.status in notifiableStatuses }
                .map { entry ->
                    FriendSportBooking(
                        isu = friend.isu,
                        lessonId = entry.prototypeLessonId,
                        entry = entry,
                    )
                }
        }

        return FriendsSportBookingsResponse(
            bookings = signedLessons + freeSignEntries + autoSignEntries,
        )
    }

    companion object {
        // The app sends only future confirmed sign-ups, a handful at a time; the cap also keeps
        // `NOT IN (:lessonIds)` far below the JDBC parameter limit.
        const val MAX_SYNCED = 500
    }
}
