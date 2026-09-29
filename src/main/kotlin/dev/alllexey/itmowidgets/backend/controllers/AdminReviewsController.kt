package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.dto.AdminReviewVerification
import dev.alllexey.itmowidgets.backend.dto.AdminReviewsSync
import dev.alllexey.itmowidgets.backend.services.AdminReviewsService
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.core.model.ApiResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/** Admin-only; the role is checked in the service. */
@RestController
@RequestMapping("/api/admin/reviews")
class AdminReviewsController(private val reviews: AdminReviewsService) {
    @GetMapping("/sync")
    fun sync(authentication: Authentication): ApiResponse<AdminReviewsSync> = ApiResponse.success(reviews.sync(authentication.uuid()))

    /** 409 `business_rule_violation` when the sync is disabled or already running. */
    @PostMapping("/sync")
    fun startSync(authentication: Authentication): ApiResponse<AdminReviewsSync> =
        ApiResponse.success(reviews.startSync(authentication.uuid()))

    /** Own reviews by the state of their ISU check; the ISU cookie itself is in `/api/admin/system/credentials`. */
    @GetMapping("/verification")
    fun verification(authentication: Authentication): ApiResponse<AdminReviewVerification> =
        ApiResponse.success(reviews.verification(authentication.uuid()))
}
