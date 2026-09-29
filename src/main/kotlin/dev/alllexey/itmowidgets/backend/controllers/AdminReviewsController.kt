package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.dto.AdminAiSummaries
import dev.alllexey.itmowidgets.backend.dto.AdminPage
import dev.alllexey.itmowidgets.backend.dto.AdminReviewVerification
import dev.alllexey.itmowidgets.backend.dto.AdminReviewsSync
import dev.alllexey.itmowidgets.backend.dto.AdminSummaryHiddenRequest
import dev.alllexey.itmowidgets.backend.dto.AdminSummaryStatus
import dev.alllexey.itmowidgets.backend.dto.AdminTeacherSummary
import dev.alllexey.itmowidgets.backend.services.AdminAiSummariesService
import dev.alllexey.itmowidgets.backend.services.AdminReviewsService
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.core.model.ApiResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/** Admin-only; the role is checked in the services. */
@RestController
@RequestMapping("/api/admin/reviews")
class AdminReviewsController(
    private val reviews: AdminReviewsService,
    private val aiSummaries: AdminAiSummariesService,
) {
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

    @GetMapping("/summaries")
    fun summaries(authentication: Authentication): ApiResponse<AdminAiSummaries> =
        ApiResponse.success(aiSummaries.state(authentication.uuid()))

    /** «Пересчитать всё»; 409 `business_rule_violation` when summaries are disabled or already running. */
    @PostMapping("/summaries/run")
    fun runSummaries(authentication: Authentication): ApiResponse<AdminAiSummaries> =
        ApiResponse.success(aiSummaries.start(authentication.uuid()))

    @GetMapping("/summaries/teachers")
    fun summaryTeachers(
        @RequestParam(required = false) status: AdminSummaryStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${AdminPage.DEFAULT_SIZE}") size: Int,
        authentication: Authentication,
    ): ApiResponse<AdminPage<AdminTeacherSummary>> =
        ApiResponse.success(aiSummaries.teachers(authentication.uuid(), status, page, size))

    /** 404 without a summary row. */
    @PutMapping("/summaries/{isu}/hidden")
    fun setSummaryHidden(
        @PathVariable isu: Int,
        @RequestBody request: AdminSummaryHiddenRequest,
        authentication: Authentication,
    ): ApiResponse<AdminTeacherSummary> = ApiResponse.success(aiSummaries.setHidden(authentication.uuid(), isu, request))

    /** 404 without a summary row; 409 with too few reviews, a hidden summary or disabled summaries. */
    @PostMapping("/summaries/{isu}/regenerate")
    fun regenerateSummary(@PathVariable isu: Int, authentication: Authentication): ApiResponse<AdminTeacherSummary> =
        ApiResponse.success(aiSummaries.regenerate(authentication.uuid(), isu))
}
