package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.services.SportFreeSignService
import dev.alllexey.itmowidgets.core.model.ApiResponse
import dev.alllexey.itmowidgets.core.model.SportFreeSignEntry
import dev.alllexey.itmowidgets.core.model.SportFreeSignRequest
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/sport/free-sign")
class SportFreeSignController(private val sportFreeSignService: SportFreeSignService) {

    @GetMapping("/all")
    fun getAllFreeSignEntries(authentication: Authentication): ApiResponse<List<SportFreeSignEntry>> {
        val userId = UUID.fromString(authentication.name)
        val entries = sportFreeSignService.getAllEntriesForUser(userId)
        return ApiResponse.success(entries)
    }

    @PostMapping("/create")
    fun createFreeSignEntry(
        @RequestBody request: SportFreeSignRequest,
        authentication: Authentication
    ): ApiResponse<SportFreeSignEntry> {
        val userId = UUID.fromString(authentication.name)
        val entry = sportFreeSignService.addToQueue(userId, request.lessonId)
        return ApiResponse.success(entry)
    }

    @DeleteMapping("/{id}")
    fun deleteFreeSignEntry(
        @PathVariable("id") id: Long,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = UUID.fromString(authentication.name)
        sportFreeSignService.removeEntryById(userId, id)
        return ApiResponse.success("Entry successfully deleted")
    }
}