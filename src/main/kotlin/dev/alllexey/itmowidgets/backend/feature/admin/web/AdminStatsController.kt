package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAudienceStatsService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminFeatureStatsService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminModerationStatsService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminServerStatsService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminSportStatsService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * Admin-only; the role is checked in the services. Every route takes the optional `from` and `to` days
 * (`YYYY-MM-DD`, Europe/Moscow, both inclusive); an unreadable date is 400 `invalid_request`.
 */
@RestController
@RequestMapping("/api/admin/stats")
class AdminStatsController(
    private val sport: AdminSportStatsService,
    private val moderation: AdminModerationStatsService,
    private val features: AdminFeatureStatsService,
    private val audience: AdminAudienceStatsService,
    private val server: AdminServerStatsService,
) {
    @GetMapping("/sport")
    fun sport(
        authentication: Authentication,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): ApiResponse<AdminSportStats> = ApiResponse.success(sport.sport(authentication.uuid(), from, to))

    @GetMapping("/moderation")
    fun moderation(
        authentication: Authentication,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): ApiResponse<AdminModerationStats> = ApiResponse.success(moderation.moderation(authentication.uuid(), from, to))

    @GetMapping("/features")
    fun features(
        authentication: Authentication,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): ApiResponse<AdminFeatureStats> = ApiResponse.success(features.features(authentication.uuid(), from, to))

    @GetMapping("/audience")
    fun audience(
        authentication: Authentication,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): ApiResponse<AdminAudienceStats> = ApiResponse.success(audience.audience(authentication.uuid(), from, to))

    @GetMapping("/server")
    fun server(
        authentication: Authentication,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): ApiResponse<AdminServerStats> = ApiResponse.success(server.server(authentication.uuid(), from, to))
}
