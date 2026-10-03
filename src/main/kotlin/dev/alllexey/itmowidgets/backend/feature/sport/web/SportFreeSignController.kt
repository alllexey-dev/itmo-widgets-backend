package dev.alllexey.itmowidgets.backend.feature.sport.web

import dev.alllexey.itmowidgets.backend.feature.sport.service.SportFreeSignService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/sport/free-sign")
class SportFreeSignController(private val sportFreeSignService: SportFreeSignService) {

    @GetMapping("/entry/my")
    fun mySportFreeSignEntries(
        authentication: Authentication
    ): ApiResponse<List<SportFreeSignEntry>> {
        val userId = authentication.uuid()
        val entries = sportFreeSignService.getUserEntries(userId)
        return ApiResponse.success(entries)
    }

    @PostMapping("/entry/create")
    fun createSportFreeSignEntry(
        @RequestBody request: SportFreeSignRequest,
        authentication: Authentication
    ): ApiResponse<SportFreeSignEntry> {
        val userId = authentication.uuid()
        val entry = sportFreeSignService.createEntry(userId, request.lessonId, request.forceSign)
        return ApiResponse.success(entry)
    }

    @PostMapping("/entry/{id}/cancel")
    fun cancelSportFreeSignEntry(
        @PathVariable id: Long,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = authentication.uuid()
        sportFreeSignService.cancelEntry(userId, id)
        return ApiResponse.success("Entry successfully cancelled")
    }

    @PostMapping("/lesson/{lessonId}/cancel")
    fun cancelSportFreeSignEntryByLesson(
        @PathVariable lessonId: Long,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = authentication.uuid()
        sportFreeSignService.cancelEntryByLesson(userId, lessonId)
        return ApiResponse.success("Entry successfully cancelled")
    }

    @GetMapping("/queue/current")
    fun currentSportFreeSignQueues(): ApiResponse<List<SportFreeSignQueue>> {
        val queues = sportFreeSignService.getCurrentQueues()
        return ApiResponse.success(queues)
    }

    @PostMapping("/entry/{id}/mark-satisfied")
    fun markSportFreeSignEntrySatisfied(
        @PathVariable id: Long,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = authentication.uuid()
        sportFreeSignService.markEntrySatisfied(userId, id)
        return ApiResponse.success("Entry marked as satisfied")
    }

    @PostMapping("/lesson/{lessonId}/mark-satisfied")
    fun markSportFreeSignEntrySatisfiedByLesson(
        @PathVariable lessonId: Long,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = authentication.uuid()
        sportFreeSignService.markEntrySatisfiedByLesson(userId, lessonId)
        return ApiResponse.success("Entry marked satisfied")
    }
}