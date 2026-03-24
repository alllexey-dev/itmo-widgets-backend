package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.services.SportAutoSignService
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.core.model.*
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/sport/auto-sign")
class SportAutoSignController(
    private val service: SportAutoSignService
) {

    @GetMapping("/limits")
    fun sportAutoSignLimits(authentication: Authentication): ApiResponse<SportAutoSignLimits> {
        val userId = authentication.uuid()
        return ApiResponse.success(service.getLimits(userId))
    }

    @GetMapping("/entry/my")
    fun mySportAutoSignEntries(authentication: Authentication): ApiResponse<List<SportAutoSignEntry>> {
        val userId = authentication.uuid()
        return ApiResponse.success(service.getUserEntries(userId))
    }

    @PostMapping("/entry/create")
    fun createSportAutoSignEntry(
        @RequestBody request: SportAutoSignRequest,
        authentication: Authentication
    ): ApiResponse<SportAutoSignEntry> {
        val userId = authentication.uuid()
        val entry = service.createEntry(userId, request.prototypeLessonId)
        return ApiResponse.success(entry)
    }

    @PostMapping("/entry/{id}/cancel")
    fun cancelSportAutoSignEntry(
        @PathVariable id: Long,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = authentication.uuid()
        service.cancelEntry(userId, id)
        return ApiResponse.success("Entry successfully cancelled")
    }

    @PostMapping("/lesson/{lessonId}/cancel")
    fun cancelSportAutoSignEntryByLesson(
        @PathVariable lessonId: Long,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = authentication.uuid()
        service.cancelEntryByLesson(userId, lessonId)
        return ApiResponse.success("Entry successfully cancelled")
    }

    @PostMapping("/queue/current")
    fun currentSportAutoSignQueues(): ApiResponse<List<SportAutoSignQueue>> {
        return ApiResponse.success(service.getCurrentQueues())
    }

    @PostMapping("/entry/{id}/mark-satisfied")
    fun markSportAutoSignEntrySatisfied(
        @PathVariable id: Long,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = authentication.uuid()
        service.markEntrySatisfied(userId, id)
        return ApiResponse.success("Entry marked satisfied")
    }

    @PostMapping("/lesson/{lessonId}/mark-satisfied")
    fun markSportAutoSignEntrySatisfiedByLesson(
        @PathVariable lessonId: Long,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = authentication.uuid()
        service.markEntrySatisfiedByLesson(userId, lessonId)
        return ApiResponse.success("Entry marked satisfied")
    }
}