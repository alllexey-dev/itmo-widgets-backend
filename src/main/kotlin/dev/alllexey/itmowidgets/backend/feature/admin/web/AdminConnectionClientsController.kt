package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminConnectionClientsService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/** The registry of connected client apps; admin-only, the role is checked in the service. */
@RestController
@RequestMapping("/api/admin/connection-clients")
class AdminConnectionClientsController(private val clients: AdminConnectionClientsService) {
    @GetMapping
    fun list(authentication: Authentication): ApiResponse<List<ConnectionClientAdmin>> =
        ApiResponse.success(clients.list(authentication.uuid()))

    /** Creates or replaces the entry; suspending it revokes the client's live connections. */
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @RequestBody request: ConnectionClientAdmin,
        authentication: Authentication,
    ): ApiResponse<ConnectionClientAdmin> = ApiResponse.success(clients.update(authentication.uuid(), id, request))
}
