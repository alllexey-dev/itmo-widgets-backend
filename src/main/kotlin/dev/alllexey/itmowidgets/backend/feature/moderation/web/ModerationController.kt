package dev.alllexey.itmowidgets.backend.feature.moderation.web

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminModerationService
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationSettingsService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/moderation")
class ModerationController(
    private val moderation: ModerationService,
    private val restrictions: RestrictionService,
    private val settings: ModerationSettingsService,
    private val adminModeration: AdminModerationService,
) {
    @GetMapping("/cases")
    fun cases(@RequestParam(defaultValue = "OPEN") status: ModerationCaseStatus, authentication: Authentication): ApiResponse<List<ModerationCase>> =
        ApiResponse.success(moderation.cases(authentication.uuid(), status))

    @PostMapping("/cases/{id}/decisions")
    fun decide(@PathVariable id: UUID, @RequestBody request: ModerationDecisionRequest, authentication: Authentication): ApiResponse<ModerationCase> =
        ApiResponse.success(moderation.decide(authentication.uuid(), id, request))

    @GetMapping("/restrictions")
    fun restrictions(@RequestParam isu: Int, authentication: Authentication): ApiResponse<List<UserRestriction>> =
        ApiResponse.success(restrictions.forUser(authentication.uuid(), isu))

    @PostMapping("/restrictions/{id}/revoke")
    fun revoke(@PathVariable id: UUID, authentication: Authentication): ApiResponse<Unit> {
        restrictions.revoke(id, authentication.uuid())
        return ApiResponse.success(Unit)
    }

    @GetMapping("/settings")
    fun settings(authentication: Authentication): ApiResponse<ModerationSettings> = ApiResponse.success(settings.settings(authentication.uuid()))

    /** Changing the policy is admin-only and audited, the same as `PUT /api/admin/moderation/settings`. */
    @PutMapping("/settings")
    fun update(@RequestBody request: ModerationSettings, authentication: Authentication): ApiResponse<ModerationSettings> =
        ApiResponse.success(adminModeration.updateSettings(authentication.uuid(), request))
}
