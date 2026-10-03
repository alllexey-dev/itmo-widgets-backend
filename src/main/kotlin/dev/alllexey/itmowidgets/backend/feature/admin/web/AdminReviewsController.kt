package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAiSummariesService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminReviewsService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
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
