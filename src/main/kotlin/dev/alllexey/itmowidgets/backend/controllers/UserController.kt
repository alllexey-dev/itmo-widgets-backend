package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.services.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.dto.UserLookupRequest
import dev.alllexey.itmowidgets.backend.dto.UserLookupResponse
import dev.alllexey.itmowidgets.backend.dto.UserProfile
import dev.alllexey.itmowidgets.backend.services.UserProfileService
import dev.alllexey.itmowidgets.backend.dto.UserData
import dev.alllexey.itmowidgets.backend.dto.UserPrivacySettings
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.backend.services.UserPrivacyService
import dev.alllexey.itmowidgets.backend.services.UserService
import dev.alllexey.itmowidgets.core.model.ApiResponse
import dev.alllexey.itmowidgets.core.model.IdTokenRequest
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService,
    private val privacyService: UserPrivacyService,
    private val profiles: UserProfileService,
    private val currentGroups: CurrentStudyGroupsService,
) {
    @GetMapping("/{isu}")
    fun profile(@PathVariable isu: Int, authentication: Authentication): ApiResponse<UserProfile> =
        ApiResponse.success(currentGroups.profile(profiles.profile(authentication.uuid(), isu)))

    @GetMapping("/{isu}/friends")
    fun friends(@PathVariable isu: Int, authentication: Authentication): ApiResponse<List<UserProfile>> =
        ApiResponse.success(currentGroups.profiles(profiles.userFriends(authentication.uuid(), isu)))

    @PostMapping("/lookup")
    fun lookup(@RequestBody request: UserLookupRequest, authentication: Authentication): ApiResponse<UserLookupResponse> =
        ApiResponse.success(profiles.lookup(authentication.uuid(), request))


    @GetMapping("/me/privacy")
    fun myPrivacy(authentication: Authentication): ApiResponse<UserPrivacySettings> {
        val user = userService.findUserById(authentication.uuid())
        return ApiResponse.success(userService.privacySettings(user))
    }

    @PutMapping("/me/privacy")
    fun updateMyPrivacy(
        @RequestBody privacy: UserPrivacySettings,
        authentication: Authentication
    ): ApiResponse<UserPrivacySettings> {
        val user = userService.findUserById(authentication.uuid())
        return ApiResponse.success(userService.updatePrivacySettings(user, privacy))
    }

    @PutMapping("/me/id-token")
    fun updateIdTokenData(@RequestBody idTokenRequest: IdTokenRequest, authentication: Authentication): ApiResponse<String> {
        val user = userService.findUserById(authentication.uuid())
        userService.updateDataFromIdToken(user, idTokenRequest.idToken)
        return ApiResponse.success("Successfully updated")
    }

    @GetMapping("/me/data")
    fun myData(authentication: Authentication): ApiResponse<UserData> {
        val user = userService.findUserById(authentication.uuid())
        return ApiResponse.success(currentGroups.userData(privacyService.userDataFor(user, user)))
    }

}
