package dev.alllexey.itmowidgets.backend.feature.reviews.web

import dev.alllexey.itmowidgets.backend.feature.links.web.ResourceVoteRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationReportRequest
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherReviewService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class TeacherReviewController(private val reviews: TeacherReviewService) {
    /** Dots next to teacher names: levels of up to 50 teachers, `?isu=1&isu=2`. */
    @GetMapping("/teachers/summary-levels")
    fun summaryLevels(@RequestParam("isu") isus: List<Int>): ApiResponse<List<TeacherSummaryLevel>> =
        ApiResponse.success(reviews.summaryLevels(isus))

    @GetMapping("/teachers/{isu}/reviews")
    fun reviews(@PathVariable isu: Int, authentication: Authentication): ApiResponse<TeacherReviewsResponse> =
        ApiResponse.success(reviews.reviews(authentication.uuid(), isu))

    /** Subject suggestions and the coach flag for the review editor; local data only. */
    @GetMapping("/teachers/{isu}/reviews/hints")
    fun hints(@PathVariable isu: Int): ApiResponse<TeacherReviewHints> = ApiResponse.success(reviews.hints(isu))

    @PutMapping("/teachers/{isu}/reviews/mine")
    fun save(
        @PathVariable isu: Int,
        @RequestBody request: SaveTeacherReviewRequest,
        authentication: Authentication,
    ): ApiResponse<TeacherReviewsResponse> = ApiResponse.success(reviews.save(authentication.uuid(), isu, request))

    @DeleteMapping("/teachers/{isu}/reviews/mine")
    fun delete(@PathVariable isu: Int, authentication: Authentication): ApiResponse<TeacherReviewsResponse> =
        ApiResponse.success(reviews.delete(authentication.uuid(), isu))

    @PutMapping("/reviews/{id}/vote")
    fun vote(
        @PathVariable id: UUID,
        @RequestBody request: ResourceVoteRequest,
        authentication: Authentication,
    ): ApiResponse<TeacherReviewsResponse> = ApiResponse.success(reviews.vote(authentication.uuid(), id, request.value))

    @PostMapping("/reviews/{id}/report")
    fun report(
        @PathVariable id: UUID,
        @RequestBody request: ModerationReportRequest,
        authentication: Authentication,
    ): ApiResponse<TeacherReviewsResponse> = ApiResponse.success(reviews.report(authentication.uuid(), id, request))
}
