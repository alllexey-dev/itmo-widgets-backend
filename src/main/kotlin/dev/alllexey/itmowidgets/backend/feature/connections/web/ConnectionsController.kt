package dev.alllexey.itmowidgets.backend.feature.connections.web

import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientHeader
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.BearerAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * «Устройства» for the app and the web (docs/contracts/connections.md#devices). Connections reach none of it but the
 * web (scope `user`); the phone-only `itmo-ended` takes the app's bearer alone.
 */
@RestController
@RequestMapping("/api/users/me")
class ConnectionsController(private val connections: ConnectionService) {
    @GetMapping("/devices")
    fun devices(authentication: Authentication, request: HttpServletRequest): ApiResponse<UserDevices> =
        ApiResponse.success(connections.devices(authentication.uuid(), callerInstall(authentication, request)))

    /** Narrowing from anywhere, widening only from the app (which asks for the device unlock first). */
    @PutMapping("/connections/{id}/scopes")
    fun updateScopes(
        @PathVariable id: UUID,
        @RequestBody body: UpdateScopesRequest,
        authentication: Authentication,
    ): ApiResponse<ConnectionInfo> = ApiResponse.success(
        connections.updateScopes(authentication.uuid(), id, body.scopes.toSet(), authentication is BearerAuthentication),
    )

    @PostMapping("/connections/{id}/revoke")
    fun revoke(@PathVariable id: UUID, authentication: Authentication): ApiResponse<Unit> {
        connections.revoke(authentication.uuid(), id)
        return ApiResponse.success(Unit)
    }

    @PostMapping("/connections/{id}/itmo-ended")
    fun itmoEnded(@PathVariable id: UUID, authentication: Authentication): ApiResponse<Unit> {
        connections.itmoEnded(authentication.phoneUser(), id)
        return ApiResponse.success(Unit)
    }

    /** Another install of the caller; signing out the calling install is the app's own sign-out. */
    @PostMapping("/installs/{instanceId}/sign-out")
    fun signOutInstall(@PathVariable instanceId: UUID, authentication: Authentication, request: HttpServletRequest): ApiResponse<Unit> {
        connections.signOutInstall(authentication.uuid(), instanceId, callerInstall(authentication, request))
        return ApiResponse.success(Unit)
    }

    private fun callerInstall(authentication: Authentication, request: HttpServletRequest): UUID? =
        if (authentication is BearerAuthentication) ClientHeader.parse(request.getHeader(ClientHeader.HEADER))?.install else null
}
