package dev.alllexey.itmowidgets.backend.feature.social.web

import dev.alllexey.itmowidgets.backend.feature.users.service.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserProfileService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserProfileService.Action
import dev.alllexey.itmowidgets.backend.feature.users.web.UserProfile
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/friends")
class FriendController(private val profiles: UserProfileService, private val currentGroups: CurrentStudyGroupsService) {
    @PostMapping("/{isu}/request")
    fun request(@PathVariable isu: Int, authentication: Authentication): ApiResponse<UserProfile> =
        ApiResponse.success(currentGroups.profile(profiles.act(authentication.uuid(), isu, Action.REQUEST)))

    @PostMapping("/{isu}/accept")
    fun accept(@PathVariable isu: Int, authentication: Authentication): ApiResponse<UserProfile> =
        ApiResponse.success(currentGroups.profile(profiles.act(authentication.uuid(), isu, Action.ACCEPT)))

    @PostMapping("/{isu}/reject")
    fun reject(@PathVariable isu: Int, authentication: Authentication): ApiResponse<UserProfile> =
        ApiResponse.success(currentGroups.profile(profiles.act(authentication.uuid(), isu, Action.REJECT)))

    @PostMapping("/{isu}/cancel")
    fun cancel(@PathVariable isu: Int, authentication: Authentication): ApiResponse<UserProfile> =
        ApiResponse.success(currentGroups.profile(profiles.act(authentication.uuid(), isu, Action.CANCEL)))

    @DeleteMapping("/{isu}")
    fun remove(@PathVariable isu: Int, authentication: Authentication): ApiResponse<UserProfile> =
        ApiResponse.success(currentGroups.profile(profiles.act(authentication.uuid(), isu, Action.REMOVE)))

    @GetMapping
    fun friends(authentication: Authentication): ApiResponse<List<UserProfile>> =
        ApiResponse.success(currentGroups.profiles(profiles.friends(authentication.uuid())))

    @GetMapping("/requests/incoming")
    fun incoming(authentication: Authentication): ApiResponse<List<UserProfile>> =
        ApiResponse.success(currentGroups.profiles(profiles.incoming(authentication.uuid())))

    @GetMapping("/requests/outgoing")
    fun outgoing(authentication: Authentication): ApiResponse<List<UserProfile>> =
        ApiResponse.success(currentGroups.profiles(profiles.outgoing(authentication.uuid())))
}
