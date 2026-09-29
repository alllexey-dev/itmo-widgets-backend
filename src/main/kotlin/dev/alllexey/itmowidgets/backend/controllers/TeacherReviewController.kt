package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.dto.ModerationReportRequest
import dev.alllexey.itmowidgets.backend.dto.ResourceVoteRequest
import dev.alllexey.itmowidgets.backend.dto.SaveTeacherReviewRequest
import dev.alllexey.itmowidgets.backend.dto.TeacherReviewsResponse
import dev.alllexey.itmowidgets.backend.services.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.services.TeacherReviewService
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.core.model.ApiResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Named authors get their current study groups after the service transaction, like every profile response. */
@RestController
@RequestMapping("/api")
class TeacherReviewController(
    private val reviews: TeacherReviewService,
    private val currentGroups: CurrentStudyGroupsService,
) {
    @GetMapping("/teachers/{isu}/reviews")
    fun reviews(@PathVariable isu: Int, authentication: Authentication): ApiResponse<TeacherReviewsResponse> =
        ApiResponse.success(decorate(reviews.reviews(authentication.uuid(), isu)))

    @PutMapping("/teachers/{isu}/reviews/mine")
    fun save(@PathVariable isu: Int, @RequestBody request: SaveTeacherReviewRequest, authentication: Authentication): ApiResponse<TeacherReviewsResponse> =
        ApiResponse.success(decorate(reviews.save(authentication.uuid(), isu, request)))

    @DeleteMapping("/teachers/{isu}/reviews/mine")
    fun delete(@PathVariable isu: Int, authentication: Authentication): ApiResponse<TeacherReviewsResponse> =
        ApiResponse.success(decorate(reviews.delete(authentication.uuid(), isu)))

    @PutMapping("/reviews/{id}/vote")
    fun vote(@PathVariable id: UUID, @RequestBody request: ResourceVoteRequest, authentication: Authentication): ApiResponse<TeacherReviewsResponse> =
        ApiResponse.success(decorate(reviews.vote(authentication.uuid(), id, request.value)))

    @PostMapping("/reviews/{id}/report")
    fun report(@PathVariable id: UUID, @RequestBody request: ModerationReportRequest, authentication: Authentication): ApiResponse<TeacherReviewsResponse> =
        ApiResponse.success(decorate(reviews.report(authentication.uuid(), id, request)))

    private fun decorate(response: TeacherReviewsResponse): TeacherReviewsResponse =
        response.copy(reviews = response.reviews.map { it.copy(author = it.author?.let(currentGroups::userData)) })
}
