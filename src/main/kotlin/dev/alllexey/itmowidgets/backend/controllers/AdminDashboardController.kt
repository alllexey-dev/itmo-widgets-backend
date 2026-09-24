package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.dto.AdminDashboard
import dev.alllexey.itmowidgets.backend.services.AdminDashboardService
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.core.model.ApiResponse
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
