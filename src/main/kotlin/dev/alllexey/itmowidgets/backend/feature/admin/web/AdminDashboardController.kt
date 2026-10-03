package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminDashboardService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Admin-only; the role is checked in the service. */
@RestController
@RequestMapping("/api/admin/dashboard")
class AdminDashboardController(private val dashboard: AdminDashboardService) {
    @GetMapping
    fun dashboard(authentication: Authentication): ApiResponse<AdminDashboard> =
        ApiResponse.success(dashboard.dashboard(authentication.uuid()))
}
