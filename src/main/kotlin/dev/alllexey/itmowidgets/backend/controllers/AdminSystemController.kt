package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.dto.AdminAppVersion
import dev.alllexey.itmowidgets.backend.dto.AdminAppVersionRequest
import dev.alllexey.itmowidgets.backend.dto.AdminSportStatus
import dev.alllexey.itmowidgets.backend.services.AdminSystemService
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.core.model.ApiResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/** Admin-only; the role is checked in the service. */
@RestController
@RequestMapping("/api/admin/system")
class AdminSystemController(private val system: AdminSystemService) {
    @GetMapping("/sport")
    fun sport(authentication: Authentication): ApiResponse<AdminSportStatus> = ApiResponse.success(system.sport(authentication.uuid()))

    @GetMapping("/app-version")
    fun appVersion(authentication: Authentication): ApiResponse<AdminAppVersion> =
        ApiResponse.success(system.appVersion(authentication.uuid()))

    @PutMapping("/app-version")
    fun updateAppVersion(@RequestBody request: AdminAppVersionRequest, authentication: Authentication): ApiResponse<AdminAppVersion> =
        ApiResponse.success(system.updateAppVersion(authentication.uuid(), request))
}
