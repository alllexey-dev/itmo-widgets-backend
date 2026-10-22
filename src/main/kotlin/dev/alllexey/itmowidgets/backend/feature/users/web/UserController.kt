package dev.alllexey.itmowidgets.backend.feature.users.web

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientHeader
import dev.alllexey.itmowidgets.backend.feature.clients.service.ClientActivityService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.feature.moderation.web.UserRestriction
import dev.alllexey.itmowidgets.backend.feature.users.service.AccountDeletionService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserProfileService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.backend.platform.security.requireSignedInWithin
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.time.Clock
import java.time.Duration

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService,
    private val privacyService: UserPrivacyService,
    private val profiles: UserProfileService,
    private val restrictions: RestrictionService,
    private val access: AdminAccess,
    private val clients: ClientActivityService,
    private val accountDeletion: AccountDeletionService,
    private val clock: Clock,
    @param:Value($$"${itmowidgets.account.recent-auth}") private val recentAuth: Duration,
) {
    @GetMapping("/{isu}")
    fun profile(@PathVariable isu: Int, authentication: Authentication): ApiResponse<UserProfile> =
        ApiResponse.success(profiles.profile(authentication.uuid(), isu))

    @GetMapping("/{isu}/friends")
    fun friends(@PathVariable isu: Int, authentication: Authentication): ApiResponse<List<UserProfile>> =
        ApiResponse.success(profiles.userFriends(authentication.uuid(), isu))

    @PostMapping("/lookup")
    fun lookup(@RequestBody request: UserLookupRequest, authentication: Authentication): ApiResponse<UserLookupResponse> =
        ApiResponse.success(profiles.lookup(authentication.uuid(), request))

    @GetMapping("/me/restrictions")
    fun myRestrictions(authentication: Authentication): ApiResponse<List<UserRestriction>> =
        ApiResponse.success(restrictions.activeFor(authentication.uuid()))

    @GetMapping("/me/roles")
    fun myRoles(authentication: Authentication): ApiResponse<List<String>> =
        ApiResponse.success(access.rolesOf(authentication.uuid()).sorted().map { it.name })

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

    /** Also records the calling install's ITMO.ID session (`X-Client` `install`) for «Устройства». */
    @PutMapping("/me/id-token")
    fun updateIdTokenData(
        @RequestBody idTokenRequest: IdTokenRequest,
        authentication: Authentication,
        request: HttpServletRequest,
    ): ApiResponse<String> {
        val user = userService.findUserById(authentication.uuid())
        val sid = userService.updateDataFromIdToken(user, idTokenRequest.idToken)
        if (sid != null) clients.recordItmoSession(user.id, ClientHeader.parse(request.getHeader(ClientHeader.HEADER)), sid)
        return ApiResponse.success("Successfully updated")
    }

    /** Turns the services on (docs/contracts/connections.md#enrollment); the first request of a new user. */
    @PutMapping("/me/consent")
    fun consent(@RequestBody request: ConsentRequest, authentication: Authentication): ApiResponse<Unit> {
        if (request.version < 1) throw InvalidRequestDataException("version must be positive")
        userService.enroll(authentication.uuid(), request.version)
        return ApiResponse.success(Unit)
    }

    @GetMapping("/me/data")
    fun myData(authentication: Authentication): ApiResponse<UserData> {
        val user = userService.findUserById(authentication.uuid())
        return ApiResponse.success(privacyService.userDataFor(user, user))
    }

    /** Deletes the caller's account (docs/ops/account-deletion.md) right after a fresh sign-in. */
    @DeleteMapping("/me")
    fun deleteMyAccount(authentication: Authentication): ApiResponse<String> {
        authentication.requireSignedInWithin(recentAuth, clock)
        accountDeletion.delete(userService.isuOf(authentication.uuid()))
        return ApiResponse.success("Account deleted")
    }
}
