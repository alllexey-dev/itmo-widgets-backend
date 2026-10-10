package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminSystemService
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/** Admin-only; the role is checked in the service. */
@RestController
@RequestMapping("/api/admin/system")
class AdminSystemController(private val system: AdminSystemService) {
    @GetMapping("/sport")
    fun sport(authentication: Authentication): ApiResponse<AdminSportStatus> = ApiResponse.success(system.sport(authentication.uuid()))

    /** Without `platform` the Android metadata, as Web reads it before its per-platform pages. */
    @GetMapping("/app-version")
    fun appVersion(
        @RequestParam(defaultValue = "ANDROID") platform: AppPlatform,
        authentication: Authentication,
    ): ApiResponse<AdminAppVersion> = ApiResponse.success(system.appVersion(authentication.uuid(), platform))

    @PutMapping("/app-version")
    fun updateAppVersion(
        @RequestParam(defaultValue = "ANDROID") platform: AppPlatform,
        @RequestBody request: AdminAppVersionRequest,
        authentication: Authentication,
    ): ApiResponse<AdminAppVersion> = ApiResponse.success(system.updateAppVersion(authentication.uuid(), platform, request))

    @GetMapping("/client-versions")
    fun clientVersions(authentication: Authentication): ApiResponse<AdminClientVersions> =
        ApiResponse.success(system.clientVersions(authentication.uuid()))

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
