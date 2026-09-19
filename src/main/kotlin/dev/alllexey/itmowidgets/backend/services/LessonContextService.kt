package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.RelationshipState
import dev.alllexey.itmowidgets.backend.dto.UserProfile
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.LessonRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * Who among the viewer's friends attends a lesson.
 *
 * The answer is bounded to accepted friends whose schedule audience admits the
 * viewer, so it never reveals more than the viewer could read through the
 * friend's own schedule; that is why no "viewer attends this lesson" check is
 * needed. A MyITMO `pair_id` names one occurrence; the date guards against rows
 * left behind by owners who stopped syncing after the pair moved.
 */
@Service
class LessonContextService(
    private val lessons: LessonRepository,
    private val users: UserRepository,
    private val friends: FriendService,
    private val privacy: UserPrivacyService,
) {

    @Transactional(readOnly = true)
    fun friendsOnLesson(viewer: User, pairId: Long, date: LocalDate): List<UserProfile> {
        val attendees = lessons.findAllUsersByPairIdAndDate(pairId, date).toSet() - viewer.isu
        if (attendees.isEmpty()) return emptyList()
        // Friend order (most recently accepted first) is the order the client shows.
        val friendsAttending = friends.getFriends(viewer.isu).filter { it in attendees }
        if (friendsAttending.isEmpty()) return emptyList()
        val found = users.findAllByIsuIn(friendsAttending).associateBy { it.isu }
        return friendsAttending
            .mapNotNull { found[it] }
            .filter { privacy.canViewSchedule(viewer, it) }
            .map { UserProfile(privacy.userDataFor(viewer, it), RelationshipState.FRIENDS) }
    }
}
