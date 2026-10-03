package dev.alllexey.itmowidgets.backend.feature.users.service

import dev.alllexey.itmowidgets.backend.feature.social.service.FriendService
import dev.alllexey.itmowidgets.backend.feature.users.model.GroupEntity
import dev.alllexey.itmowidgets.backend.feature.users.model.GroupEntity.Companion.toDto
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.web.UserCapabilities
import dev.alllexey.itmowidgets.backend.feature.users.web.UserData
import org.springframework.stereotype.Service

/** Owner-only policy: the viewer's sharing preference never restricts another owner's ALL audience. */
@Service
class UserPrivacyService(private val friendService: FriendService) {
    fun canViewSchedule(viewer: User, owner: User): Boolean = canView(viewer, owner, owner.settings.scheduleVisibility)

    fun canViewSport(viewer: User, owner: User): Boolean = canView(viewer, owner, owner.settings.sportVisibility)

    fun canViewFriends(viewer: User, owner: User): Boolean = canView(viewer, owner, owner.settings.friendsVisibility)

    fun userDataFor(viewer: User, owner: User): UserData = UserData(
        isu = owner.isu,
        // Empty means "not published yet"; clients render their own placeholder.
        name = owner.name ?: "",
        pictureUrl = owner.pictureUrl,
        // Stored ID-token groups include earlier years; the highest course is the
        // best guess at the current one when the directory read below is unavailable.
        groups = owner.groups.sortedWith(compareByDescending<GroupEntity> { it.course }.thenBy { it.name }).map { it.toDto() },
        capabilities = UserCapabilities(
            canViewSchedule = canViewSchedule(viewer, owner),
            canViewSport = canViewSport(viewer, owner),
            canViewFriends = canViewFriends(viewer, owner),
        ),
    )

    private fun canView(viewer: User, owner: User, visibility: SharingVisibility): Boolean {
        if (viewer.id == owner.id) return true
        return when (visibility) {
            SharingVisibility.ALL -> true
            SharingVisibility.FRIENDS -> friendService.areFriends(viewer.isu, owner.isu)
            SharingVisibility.NOBODY -> false
        }
    }
}
