package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminModerationService
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCase
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationDecisionRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationSettings
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

/** Moderators and admins; the policy routes are admin-only. Roles are checked in the service. */
@RestController
@RequestMapping("/api/admin/moderation")
class AdminModerationController(private val moderation: AdminModerationService) {
    @GetMapping("/cases")
    fun cases(
        @RequestParam(defaultValue = "OPEN") status: ModerationCaseStatus,
        @RequestParam(required = false) reason: ModerationCaseReason?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${AdminPage.DEFAULT_SIZE}") size: Int,
        authentication: Authentication,
    ): ApiResponse<AdminPage<AdminCaseItem>> = ApiResponse.success(moderation.cases(authentication.uuid(), status, reason, page, size))

    @GetMapping("/cases/{id}")
    fun case(@PathVariable id: UUID, authentication: Authentication): ApiResponse<ModerationCase> =
        ApiResponse.success(moderation.case(authentication.uuid(), id))

    @PostMapping("/cases/{id}/decisions")
    fun decide(
        @PathVariable id: UUID,
        @RequestBody request: ModerationDecisionRequest,
        authentication: Authentication,
    ): ApiResponse<ModerationCase> = ApiResponse.success(moderation.decide(authentication.uuid(), id, request))

    @GetMapping("/restrictions")
    fun restrictions(
        @RequestParam(required = false) isu: Int?,
        @RequestParam(defaultValue = "true") active: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${AdminPage.DEFAULT_SIZE}") size: Int,
        authentication: Authentication,
    ): ApiResponse<AdminPage<AdminRestriction>> =
        ApiResponse.success(moderation.restrictions(authentication.uuid(), isu, active, page, size))

    @PostMapping("/restrictions/{id}/revoke")
    fun revoke(@PathVariable id: UUID, authentication: Authentication): ApiResponse<Unit> {
        moderation.revokeRestriction(authentication.uuid(), id)
        return ApiResponse.success(Unit)
    }

    @GetMapping("/settings")
    fun settings(authentication: Authentication): ApiResponse<ModerationSettings> =
        ApiResponse.success(moderation.settings(authentication.uuid()))

    @PutMapping("/settings")
    fun updateSettings(@RequestBody request: ModerationSettings, authentication: Authentication): ApiResponse<ModerationSettings> =
        ApiResponse.success(moderation.updateSettings(authentication.uuid(), request))
}
