package dev.alllexey.itmowidgets.backend.feature.users.service

import dev.alllexey.itmowidgets.backend.feature.social.service.FriendService
import dev.alllexey.itmowidgets.backend.feature.users.model.GroupEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.GroupEntity.Companion.toDto
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.web.UserCapabilities
import dev.alllexey.itmowidgets.backend.feature.users.web.UserData
import org.springframework.stereotype.Service

/**
 * Owner-only policy: the viewer's sharing preference never restricts another owner's ALL audience. A FRIENDS audience
 * reads the friendship at most once per owner; the `...OfFriend` variants take it as known, for a caller that has just
 * read the owner from the viewer's accepted friends (`FriendService.getFriends`), so a list of friends costs no
 * friendship read per owner.
 */
@Service
class UserPrivacyService(private val friendService: FriendService) {
    fun canViewSchedule(viewer: User, owner: User): Boolean =
        canView(viewer, owner, owner.settings.scheduleVisibility, friendship(viewer, owner))

    fun canViewSport(viewer: User, owner: User): Boolean = canView(viewer, owner, owner.settings.sportVisibility, friendship(viewer, owner))

    fun canViewFriends(viewer: User, owner: User): Boolean =
        canView(viewer, owner, owner.settings.friendsVisibility, friendship(viewer, owner))

    fun userDataFor(viewer: User, owner: User): UserData = userDataFor(viewer, owner, friendship(viewer, owner))

    /** [canViewSchedule] for an accepted friend of [viewer]. */
    fun canViewScheduleOfFriend(viewer: User, friend: User): Boolean = canView(viewer, friend, friend.settings.scheduleVisibility, FRIENDS)

    /** [userDataFor] an accepted friend of [viewer]. */
    fun userDataForFriend(viewer: User, friend: User): UserData = userDataFor(viewer, friend, FRIENDS)

    private fun userDataFor(viewer: User, owner: User, friends: Lazy<Boolean>): UserData = UserData(
        isu = owner.isu,
        // Empty means "not published yet"; clients render their own placeholder.
        name = owner.name ?: "",
        pictureUrl = owner.pictureUrl,
        // Stored ID-token groups include earlier years; the highest course is the best guess at the current one.
        groups = owner.groups.sortedWith(compareByDescending<GroupEntity> { it.course }.thenBy { it.name }).map { it.toDto() },
        capabilities = UserCapabilities(
            canViewSchedule = canView(viewer, owner, owner.settings.scheduleVisibility, friends),
            canViewSport = canView(viewer, owner, owner.settings.sportVisibility, friends),
            canViewFriends = canView(viewer, owner, owner.settings.friendsVisibility, friends),
        ),
        gender = owner.gender,
        familyName = owner.familyName,
        givenName = owner.givenName,
    )

    private fun friendship(viewer: User, owner: User): Lazy<Boolean> = lazy { friendService.areFriends(viewer.isu, owner.isu) }

    private fun canView(viewer: User, owner: User, visibility: SharingVisibility, friends: Lazy<Boolean>): Boolean {
        if (viewer.id == owner.id) return true
        return when (visibility) {
            SharingVisibility.ALL -> true
            SharingVisibility.FRIENDS -> friends.value
            SharingVisibility.NOBODY -> false
        }
    }

    private companion object {
        val FRIENDS: Lazy<Boolean> = lazyOf(true)
    }
}
