package dev.alllexey.itmowidgets.backend.feature.users.web

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.feature.moderation.web.UserRestriction
import dev.alllexey.itmowidgets.backend.feature.users.service.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserProfileService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebLoginService
import dev.alllexey.itmowidgets.backend.feature.weblogin.web.WebLoginPreview
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.backend.platform.security.WebSessionAuthentication
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService,
    private val privacyService: UserPrivacyService,
    private val profiles: UserProfileService,
    private val currentGroups: CurrentStudyGroupsService,
    private val restrictions: RestrictionService,
    private val access: AdminAccess,
    private val webLogins: WebLoginService,
) {
    @GetMapping("/{isu}")
    fun profile(@PathVariable isu: Int, authentication: Authentication): ApiResponse<UserProfile> =
        ApiResponse.success(currentGroups.profile(profiles.profile(authentication.uuid(), isu)))

    @GetMapping("/{isu}/friends")
    fun friends(@PathVariable isu: Int, authentication: Authentication): ApiResponse<List<UserProfile>> =
        ApiResponse.success(currentGroups.profiles(profiles.userFriends(authentication.uuid(), isu)))

    @PostMapping("/lookup")
    fun lookup(@RequestBody request: UserLookupRequest, authentication: Authentication): ApiResponse<UserLookupResponse> =
        ApiResponse.success(currentGroups.lookup(profiles.lookup(authentication.uuid(), request)))

    @GetMapping("/me/restrictions")
    fun myRestrictions(authentication: Authentication): ApiResponse<List<UserRestriction>> =
        ApiResponse.success(restrictions.activeFor(authentication.uuid()))

    @GetMapping("/me/roles")
    fun myRoles(authentication: Authentication): ApiResponse<List<String>> =
        ApiResponse.success(access.rolesOf(authentication.uuid()).sorted().map { it.name })

    @GetMapping("/me/web-login/{code}")
    fun webLoginPreview(@PathVariable code: String, authentication: Authentication): ApiResponse<WebLoginPreview> {
        requireAppToken(authentication)
        return ApiResponse.success(webLogins.preview(authentication.uuid(), code))
    }

    @PostMapping("/me/web-login/{challengeId}/approve")
    fun approveWebLogin(@PathVariable challengeId: UUID, authentication: Authentication): ApiResponse<Unit> {
        requireAppToken(authentication)
        webLogins.approve(authentication.uuid(), challengeId)
        return ApiResponse.success(Unit)
    }

    @GetMapping("/me/privacy")
    fun myPrivacy(authentication: Authentication): ApiResponse<UserPrivacySettings> {
        val user = userService.findUserById(authentication.uuid())
        return ApiResponse.success(userService.privacySettings(user))
    }

    @PutMapping("/me/privacy")
    fun updateMyPrivacy(@RequestBody privacy: UserPrivacySettings, authentication: Authentication): ApiResponse<UserPrivacySettings> {
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

    /** The web session filter already skips these routes; a web session must never approve another browser. */
    private fun requireAppToken(authentication: Authentication) {
        if (authentication is WebSessionAuthentication) throw PermissionDeniedException("Web login is approved from the app")
    }
}
