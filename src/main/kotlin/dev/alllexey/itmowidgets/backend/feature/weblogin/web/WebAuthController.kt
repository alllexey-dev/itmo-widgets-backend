package dev.alllexey.itmowidgets.backend.feature.weblogin.web

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.weblogin.model.PairingKind
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.ConnectionNotificationService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebLoginService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionConfig
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.http.ClientAddress
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.backend.platform.security.WebSessionCookie
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/web/auth")
class WebAuthController(
    private val logins: WebLoginService,
    private val sessions: WebSessionService,
    private val sessionConfig: WebSessionConfig,
    private val userService: UserService,
    private val privacyService: UserPrivacyService,
    private val access: AdminAccess,
    private val connections: ConnectionNotificationService,
) {
    @PostMapping("/challenges")
    fun createChallenge(request: HttpServletRequest): ApiResponse<CreatedChallenge> =
        ApiResponse.success(logins.createChallenge(request.getHeader(HttpHeaders.USER_AGENT), ClientAddress.of(request)))

    /** Sets the session cookie only on the poll that turns the approval into a session. */
    @GetMapping("/challenges/{id}")
    fun poll(@PathVariable id: UUID, @RequestHeader(POLL_SECRET_HEADER) pollSecret: String): ResponseEntity<ApiResponse<WebLoginPoll>> =
        when (val result = logins.claim(id, pollSecret)) {
            ClaimResult.Pending -> ResponseEntity.ok(ApiResponse.success(WebLoginPoll(WebLoginPollStatus.PENDING)))

            ClaimResult.Expired -> ResponseEntity.ok(ApiResponse.success(WebLoginPoll(WebLoginPollStatus.EXPIRED)))

            is ClaimResult.Approved -> {
                if (result.userId != null && result.sessionId != null) {
                    connections.notifyConnected(result.userId, result.sessionId, PairingKind.WEB_SESSION, null, result.userAgent)
                }
                ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, WebSessionCookie.issue(result.sessionToken, sessionConfig.maxLifetime).toString())
                    .body(ApiResponse.success(WebLoginPoll(WebLoginPollStatus.APPROVED)))
            }
        }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<ApiResponse<Unit>> {
        WebSessionCookie.read(request)?.let(sessions::revoke)
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, WebSessionCookie.clear().toString())
            .body(ApiResponse.success(Unit))
    }

    @GetMapping("/me")
    fun me(authentication: Authentication): ApiResponse<WebMe> {
        val user = userService.findUserById(authentication.uuid())
        val data = privacyService.userDataFor(user, user)
        return ApiResponse.success(
            WebMe(
                data.isu,
                data.name,
                data.pictureUrl,
                data.groups,
                access.rolesOf(user.id).sorted().map { it.name },
            ),
        )
    }

    companion object {
        const val POLL_SECRET_HEADER = "X-Poll-Secret"
    }
}
