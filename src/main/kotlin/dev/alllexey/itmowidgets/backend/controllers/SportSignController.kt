package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.backend.services.UserSportLessonService
import dev.alllexey.itmowidgets.core.model.ApiResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sport/sign")
class SportSignController(private val userSportLessonService: UserSportLessonService) {

    @PostMapping("/sync")
    fun sync(@RequestBody lessonIds: List<Long>, authentication: Authentication): ApiResponse<String> {
        val userId = authentication.uuid()
        userSportLessonService.syncLessons(userId, lessonIds)
        return ApiResponse.success("Successfully synced")
    }
}