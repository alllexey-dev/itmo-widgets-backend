package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.configs.WebSessionCookie
import dev.alllexey.itmowidgets.backend.dto.ClaimResult
import dev.alllexey.itmowidgets.backend.dto.CreatedChallenge
import dev.alllexey.itmowidgets.backend.dto.WebLoginPoll
import dev.alllexey.itmowidgets.backend.dto.WebLoginPollStatus
import dev.alllexey.itmowidgets.backend.dto.WebMe
import dev.alllexey.itmowidgets.backend.services.AdminAccess
import dev.alllexey.itmowidgets.backend.services.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.backend.services.UserPrivacyService
import dev.alllexey.itmowidgets.backend.services.UserService
import dev.alllexey.itmowidgets.backend.services.WebLoginService
import dev.alllexey.itmowidgets.backend.services.WebSessionService
import dev.alllexey.itmowidgets.core.model.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.net.InetAddress
import java.util.UUID

@RestController
@RequestMapping("/api/web/auth")
class WebAuthController(
    private val logins: WebLoginService,
    private val sessions: WebSessionService,
    private val userService: UserService,
    private val privacyService: UserPrivacyService,
    private val currentGroups: CurrentStudyGroupsService,
    private val access: AdminAccess,
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
            is ClaimResult.Approved -> ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, WebSessionCookie.issue(result.sessionToken).toString())
                .body(ApiResponse.success(WebLoginPoll(WebLoginPollStatus.APPROVED)))
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
        val data = currentGroups.userData(privacyService.userDataFor(user, user))
        return ApiResponse.success(WebMe(data.isu, data.name, data.pictureUrl, data.groups,
            access.rolesOf(user.id).sorted().map { it.name }))
    }

    companion object {
        const val POLL_SECRET_HEADER = "X-Poll-Secret"
    }
}

/**
 * The browser's address for login rate limiting. The backend port is reachable only through nginx-hub on the
 * private Docker network, and nginx overwrites `X-Real-IP` with its `$remote_addr`, so that header is trusted
 * only when the direct peer is a private or loopback address (the proxy). `X-Forwarded-For` is never used: its
 * leftmost entries come from the client. `server.forward-headers-strategy` stays unset to keep request.remoteAddr
 * the proxy's own address.
 */
internal object ClientAddress {
    private val literal = Regex("^[0-9A-Fa-f:.]{2,64}$")

    fun of(request: HttpServletRequest): String {
        val peer = request.remoteAddr.orEmpty()
        val forwarded = request.getHeader("X-Real-IP")?.trim()
        return if (forwarded != null && literal.matches(forwarded) && isProxy(peer)) forwarded else peer.take(64)
    }

    private fun isProxy(peer: String): Boolean {
        if (!literal.matches(peer)) return false
        // A literal address is parsed without a DNS lookup.
        val address = runCatching { InetAddress.getByName(peer) }.getOrNull() ?: return false
        return address.isLoopbackAddress || address.isSiteLocalAddress
    }
}
