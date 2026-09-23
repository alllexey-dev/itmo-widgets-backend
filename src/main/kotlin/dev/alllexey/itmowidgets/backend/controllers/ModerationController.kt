package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.dto.*
import dev.alllexey.itmowidgets.backend.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.services.*
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.core.model.ApiResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/moderation")
class ModerationController(
    private val moderation: ModerationService,
    private val restrictions: RestrictionService,
    private val settings: ModerationSettingsService,
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

    @PutMapping("/settings")
    fun update(@RequestBody request: ModerationSettings, authentication: Authentication): ApiResponse<ModerationSettings> =
        ApiResponse.success(settings.update(authentication.uuid(), request))
}
