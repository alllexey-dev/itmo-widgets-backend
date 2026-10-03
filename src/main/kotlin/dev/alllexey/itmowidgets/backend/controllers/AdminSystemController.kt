package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.dto.AdminAppVersion
import dev.alllexey.itmowidgets.backend.dto.AdminAppVersionRequest
import dev.alllexey.itmowidgets.backend.dto.AdminServiceCredential
import dev.alllexey.itmowidgets.backend.dto.AdminSportStatus
import dev.alllexey.itmowidgets.backend.dto.ServiceCredentialRequest
import dev.alllexey.itmowidgets.backend.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.services.AdminSystemService
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.backend.dto.ApiResponse
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

    @GetMapping("/credentials")
    fun credentials(authentication: Authentication): ApiResponse<List<AdminServiceCredential>> =
        ApiResponse.success(system.credentials(authentication.uuid()))

    /** An unknown key is a 400 `invalid_request` from the path conversion. */
    @PutMapping("/credentials/{key}")
    fun replaceCredential(
        @PathVariable key: ServiceCredential,
        @RequestBody request: ServiceCredentialRequest,
        authentication: Authentication,
    ): ApiResponse<List<AdminServiceCredential>> = ApiResponse.success(system.replaceCredential(authentication.uuid(), key, request))
}
