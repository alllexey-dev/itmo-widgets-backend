package dev.alllexey.itmowidgets.backend.controllers

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
class UserController(private val userService: UserService, private val privacyService: UserPrivacyService) {

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
        return ApiResponse.success(privacyService.userDataFor(user, user))
    }

}
