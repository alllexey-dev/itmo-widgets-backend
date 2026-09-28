package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.dto.TeacherReviewsResponse
import dev.alllexey.itmowidgets.backend.services.TeacherReviewService
import dev.alllexey.itmowidgets.core.model.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/teachers")
class TeacherReviewController(private val reviews: TeacherReviewService) {
    @GetMapping("/{isu}/reviews")
    fun reviews(@PathVariable isu: Int): ApiResponse<TeacherReviewsResponse> = ApiResponse.success(reviews.reviews(isu))
}
