package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.services.SportAutoSignService
import dev.alllexey.itmowidgets.core.model.*
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/sport/auto-sign")
class SportAutoSignController(
    private val service: SportAutoSignService
) {

    @GetMapping("/limits")
    fun sportAutoSignLimits(authentication: Authentication): ApiResponse<SportAutoSignLimits> {
        val userId = UUID.fromString(authentication.name)
        return ApiResponse.success(service.getLimits(userId))
    }

    @GetMapping("/entry/my")
    fun mySportAutoSignEntries(authentication: Authentication): ApiResponse<List<SportAutoSignEntry>> {
        val userId = UUID.fromString(authentication.name)
        return ApiResponse.success(service.getMyEntries(userId))
    }

    @PostMapping("/entry/create")
    fun createSportAutoSignEntry(
        @RequestBody request: SportAutoSignRequest,
        authentication: Authentication
    ): ApiResponse<SportAutoSignEntry> {
        val userId = UUID.fromString(authentication.name)
        val entry = service.createEntry(userId, request.prototypeLessonId)
        return ApiResponse.success(entry)
    }

    @DeleteMapping("/entry/{id}")
    fun deleteSportAutoSignEntry(
        @PathVariable("id") id: Long,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = UUID.fromString(authentication.name)
        service.deleteEntry(userId, id)
        return ApiResponse.success("Entry successfully deleted")
    }

    @PostMapping("/queue/current")
    fun currentSportAutoSignQueues(): ApiResponse<List<SportAutoSignQueue>> {
        return ApiResponse.success(service.getCurrentQueues())
    }

    @PostMapping("/entry/{id}/mark-satisfied")
    fun markSportAutoSignEntrySatisfied(
        @PathVariable("id") id: Long,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = UUID.fromString(authentication.name)
        service.markSatisfied(userId, id)
        return ApiResponse.success("Entry marked satisfied")
    }
}