package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.dto.UserProfile
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.backend.services.UserProfileService
import dev.alllexey.itmowidgets.backend.services.UserProfileService.Action
import dev.alllexey.itmowidgets.core.model.ApiResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/friends")
class FriendController(private val profiles: UserProfileService) {
    @PostMapping("/{isu}/request")
    fun request(@PathVariable isu: Int, authentication: Authentication): ApiResponse<UserProfile> =
        ApiResponse.success(profiles.act(authentication.uuid(), isu, Action.REQUEST))

    @PostMapping("/{isu}/accept")
    fun accept(@PathVariable isu: Int, authentication: Authentication): ApiResponse<UserProfile> =
        ApiResponse.success(profiles.act(authentication.uuid(), isu, Action.ACCEPT))

    @PostMapping("/{isu}/reject")
    fun reject(@PathVariable isu: Int, authentication: Authentication): ApiResponse<UserProfile> =
        ApiResponse.success(profiles.act(authentication.uuid(), isu, Action.REJECT))

    @PostMapping("/{isu}/cancel")
    fun cancel(@PathVariable isu: Int, authentication: Authentication): ApiResponse<UserProfile> =
        ApiResponse.success(profiles.act(authentication.uuid(), isu, Action.CANCEL))

    @DeleteMapping("/{isu}")
    fun remove(@PathVariable isu: Int, authentication: Authentication): ApiResponse<UserProfile> =
        ApiResponse.success(profiles.act(authentication.uuid(), isu, Action.REMOVE))

    @GetMapping
    fun friends(authentication: Authentication): ApiResponse<List<UserProfile>> =
        ApiResponse.success(profiles.friends(authentication.uuid()))

    @GetMapping("/requests/incoming")
    fun incoming(authentication: Authentication): ApiResponse<List<UserProfile>> =
        ApiResponse.success(profiles.incoming(authentication.uuid()))

    @GetMapping("/requests/outgoing")
    fun outgoing(authentication: Authentication): ApiResponse<List<UserProfile>> =
        ApiResponse.success(profiles.outgoing(authentication.uuid()))
}
