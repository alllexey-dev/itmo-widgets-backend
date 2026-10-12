package dev.alllexey.itmowidgets.backend.feature.weblogin.web

import dev.alllexey.itmowidgets.backend.feature.weblogin.service.ConnectionsService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.PairingService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.platform.security.BearerAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.backend.platform.security.WebSessionAuthentication
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * The phone's side of pairing (open, approve, reject: the app's ITMO.ID bearer only, the cookie is ignored there) and the
 * connections list (bearer or web session). Agent tokens reach none of it (`AgentRoutePolicy`).
 */
@RestController
@RequestMapping("/api/users/me")
class PairingController(private val pairings: PairingService, private val connections: ConnectionsService) {
    @PostMapping("/pair/{code}/open")
    fun openPairing(@PathVariable code: String, authentication: Authentication): ApiResponse<PairingPreview> {
        val view = pairings.open(authentication.appUser(), code)
        return ApiResponse.success(
            PairingPreview(
                view.challengeId,
                view.kind,
                view.name,
                view.version,
                view.userAgent,
                view.keyCommitment?.let(PairingService::encode),
                view.scopes?.sorted(),
                view.createdAt,
                view.expiresAt,
            ),
        )
    }

    @PostMapping("/pair/{challengeId}/approve")
    fun approvePairing(
        @PathVariable challengeId: UUID,
        @RequestBody request: ApprovePairingRequest,
        authentication: Authentication,
    ): ApiResponse<Unit> {
        pairings.approve(authentication.appUser(), challengeId, request.sealedBundle, request.scopes?.toSet())
        return ApiResponse.success(Unit)
    }

    @PostMapping("/pair/{challengeId}/reject")
    fun rejectPairing(@PathVariable challengeId: UUID, authentication: Authentication): ApiResponse<Unit> {
        pairings.reject(authentication.appUser(), challengeId)
        return ApiResponse.success(Unit)
    }

    @GetMapping("/connections")
    fun connections(authentication: Authentication): ApiResponse<List<ConnectionInfo>> =
        ApiResponse.success(connections.list(authentication.uuid(), (authentication as? WebSessionAuthentication)?.sessionId))

    @PostMapping("/connections/{id}/revoke")
    fun revokeConnection(@PathVariable id: UUID, authentication: Authentication): ApiResponse<Unit> {
        connections.revoke(authentication.uuid(), id)
        return ApiResponse.success(Unit)
    }

    /** Only the phone approves a device; the security chain already ignores the cookie here. */
    private fun Authentication.appUser(): UUID =
        if (this is BearerAuthentication) uuid() else throw PermissionDeniedException("Devices are connected from the app")
}
