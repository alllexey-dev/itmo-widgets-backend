package dev.alllexey.itmowidgets.backend.feature.schedule.service

import dev.alllexey.itmowidgets.backend.feature.schedule.model.LessonEntity.Companion.toDto
import dev.alllexey.itmowidgets.backend.feature.schedule.persistence.LessonRepository
import dev.alllexey.itmowidgets.backend.feature.schedule.web.FriendDay
import dev.alllexey.itmowidgets.backend.feature.social.service.FriendService
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.web.RelationshipState
import dev.alllexey.itmowidgets.backend.feature.users.web.UserProfile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the viewer's friends attend: one lesson, or one day.
 *
 * Both answers are bounded to accepted friends whose schedule audience admits the viewer, so they never reveal more
 * than the viewer could read through each friend's own schedule; that is why no "viewer attends this lesson" check is
 * needed. Friend order (most recently accepted first) is the order the client shows. Each answer takes a fixed number
 * of queries whatever the number of friends: the friend list, the friends with their settings and groups, the lessons.
 */
@Service
class LessonContextService(
    private val lessons: LessonRepository,
    private val users: UserRepository,
    private val friends: FriendService,
    private val privacy: UserPrivacyService,
    private val clock: Clock,
) {

    /** A MyITMO `pair_id` names one occurrence; the date guards against rows of owners who stopped syncing after the pair moved. */
    @Transactional(readOnly = true)
    fun friendsOnLesson(viewer: User, pairId: Long, date: LocalDate): List<UserProfile> {
        val attendees = lessons.findAllUsersByPairIdAndDate(pairId, date).toSet() - viewer.isu
        if (attendees.isEmpty()) return emptyList()
        return scheduleAudience(viewer, friends.getFriends(viewer.isu).filter { it in attendees })
            .map { UserProfile(privacy.userDataForFriend(viewer, it), RelationshipState.FRIENDS) }
    }

    /** Friends with a lesson on [date] (a Europe/Moscow day, today when null); a friend without one is left out. */
    @Transactional(readOnly = true)
    fun friendsOnDay(viewer: User, date: LocalDate?): List<FriendDay> {
        val day = date ?: LocalDate.ofInstant(Instant.now(clock), MOSCOW)
        val visible = scheduleAudience(viewer, friends.getFriends(viewer.isu))
        if (visible.isEmpty()) return emptyList()
        val byOwner = lessons.findAllByIsuInAndDate(visible.map { it.isu }, day).groupBy { it.userIsu }
        return visible.mapNotNull { friend ->
            byOwner[friend.isu]?.let { FriendDay(privacy.userDataForFriend(viewer, friend), it.map { lesson -> lesson.toDto() }) }
        }
    }

    /** Of [friendIsus], accepted friends of [viewer] in friend order, the users whose schedule audience admits the viewer. */
    private fun scheduleAudience(viewer: User, friendIsus: List<Int>): List<User> {
        if (friendIsus.isEmpty()) return emptyList()
        val found = users.findAllByIsuIn(friendIsus).associateBy { it.isu }
        return friendIsus.mapNotNull { found[it] }.filter { privacy.canViewScheduleOfFriend(viewer, it) }
    }

    private companion object {
        val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
