package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity.Companion.toDto
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.backend.services.UserService
import dev.alllexey.itmowidgets.core.model.ApiResponse
import dev.alllexey.itmowidgets.core.model.IdTokenRequest
import dev.alllexey.itmowidgets.core.model.UserSettings
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/users")
class UserController(private val userService: UserService) {

    @GetMapping("/me/settings")
    fun mySettings(authentication: Authentication): ApiResponse<UserSettings> {
        val user = userService.findUserById(authentication.uuid())
        return ApiResponse.success(user.settings.toDto())
    }

    @PutMapping("/me/settings")
    fun updateMySettings(@RequestBody settings: UserSettings, authentication: Authentication): ApiResponse<String> {
        val user = userService.findUserById(authentication.uuid())
        userService.updateSettings(user, settings)
        return ApiResponse.success("Successfully synced")
    }

    @PutMapping("/me/id-token")
    fun updateIdTokenData(@RequestBody idTokenRequest: IdTokenRequest, authentication: Authentication): ApiResponse<String> {
        val user = userService.findUserById(authentication.uuid())
        userService.updateDataFromIdToken(user, idTokenRequest.idToken)
        return ApiResponse.success("Successfully updated")
    }

}