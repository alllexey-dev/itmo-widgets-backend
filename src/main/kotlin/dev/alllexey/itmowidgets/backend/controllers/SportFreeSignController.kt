package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.services.SportFreeSignService
import dev.alllexey.itmowidgets.core.model.ApiResponse
import dev.alllexey.itmowidgets.core.model.SportFreeSignEntry
import dev.alllexey.itmowidgets.core.model.SportFreeSignQueue
import dev.alllexey.itmowidgets.core.model.SportFreeSignRequest
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/sport/free-sign")
class SportFreeSignController(private val sportFreeSignService: SportFreeSignService) {

    @GetMapping("/entry/my")
    fun mySportFreeSignEntries(
        authentication: Authentication
    ): ApiResponse<List<SportFreeSignEntry>> {
        val userId = UUID.fromString(authentication.name)
        val entries = sportFreeSignService.getMyEntries(userId)
        return ApiResponse.success(entries)
    }

    @PostMapping("/entry/create")
    fun createSportFreeSignEntry(
        @RequestBody request: SportFreeSignRequest,
        authentication: Authentication
    ): ApiResponse<SportFreeSignEntry> {
        val userId = UUID.fromString(authentication.name)
        val entry = sportFreeSignService.addToQueue(userId, request.lessonId, request.forceSign ?: false)
        return ApiResponse.success(entry)
    }

    @DeleteMapping("/entry/{id}")
    fun deleteSportFreeSignEntry(
        @PathVariable("id") id: Long,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = UUID.fromString(authentication.name)
        sportFreeSignService.removeEntryById(userId, id)
        return ApiResponse.success("Entry successfully deleted")
    }

    @GetMapping("/queue/current")
    fun currentSportFreeSignQueues(): ApiResponse<List<SportFreeSignQueue>> {
        val queues = sportFreeSignService.getCurrentQueues()
        return ApiResponse.success(queues)
    }

    @PostMapping("/entry/{id}/mark-satisfied")
    fun markSportFreeSignEntrySatisfied(
        @PathVariable("id") id: Long,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = UUID.fromString(authentication.name)
        sportFreeSignService.markEntrySatisfied(userId, id)
        return ApiResponse.success("Entry marked as satisfied")
    }
}