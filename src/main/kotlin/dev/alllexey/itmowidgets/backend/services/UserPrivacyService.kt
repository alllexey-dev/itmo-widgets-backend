package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.User.Companion.toDto
import dev.alllexey.itmowidgets.core.model.UserData
import dev.alllexey.itmowidgets.core.model.UserSettings
import org.springframework.stereotype.Service

/** Owner-only policy: the viewer's sharing preference never restricts another owner's ALL audience. */
@Service
class UserPrivacyService(private val friendService: FriendService) {
    fun canViewSchedule(viewer: User, owner: User): Boolean =
        canView(viewer, owner, owner.settings.effectiveScheduleVisibility())

    fun canViewSport(viewer: User, owner: User): Boolean =
        canView(viewer, owner, owner.settings.effectiveSportVisibility())

    /** Preserve the legacy wire shape without exposing another user's raw privacy settings. */
    fun userDataFor(viewer: User, owner: User): UserData = owner.toDto().copy(
        settings = UserSettings(
            sportSharing = canViewSport(viewer, owner),
            scheduleSharing = canViewSchedule(viewer, owner)
        )
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
