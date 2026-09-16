package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.UserCapabilities
import dev.alllexey.itmowidgets.backend.dto.UserData
import dev.alllexey.itmowidgets.backend.model.GroupEntity.Companion.toDto
import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.User
import org.springframework.stereotype.Service

/** Owner-only policy: the viewer's sharing preference never restricts another owner's ALL audience. */
@Service
class UserPrivacyService(private val friendService: FriendService) {
    fun canViewSchedule(viewer: User, owner: User): Boolean =
        canView(viewer, owner, owner.settings.scheduleVisibility)

    fun canViewSport(viewer: User, owner: User): Boolean =
        canView(viewer, owner, owner.settings.sportVisibility)

    fun canViewFriends(viewer: User, owner: User): Boolean =
        canView(viewer, owner, owner.settings.friendsVisibility)

    fun userDataFor(viewer: User, owner: User): UserData = UserData(
        isu = owner.isu,
        name = owner.name ?: "Нет данных",
        pictureUrl = owner.pictureUrl,
        groups = owner.groups.map { it.toDto() },
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
