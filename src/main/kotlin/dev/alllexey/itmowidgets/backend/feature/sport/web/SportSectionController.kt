package dev.alllexey.itmowidgets.backend.feature.sport.web

import dev.alllexey.itmowidgets.backend.feature.sport.service.SportSectionService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sport/sections")
class SportSectionController(private val service: SportSectionService) {

    @GetMapping("/my")
    fun mySportSections(authentication: Authentication): ApiResponse<List<MySportSection>> =
        ApiResponse.success(service.getMine(authentication.uuid()))

    @GetMapping("/slots")
    fun sportSectionSlots(@RequestParam lessonIds: List<Long>, authentication: Authentication): ApiResponse<List<SportSectionSlot>> =
        ApiResponse.success(service.getSlots(authentication.uuid(), lessonIds))

    @PostMapping("/create")
    fun createSportSection(@RequestBody request: SportSectionRequest, authentication: Authentication): ApiResponse<MySportSection> =
        ApiResponse.success(service.subscribe(authentication.uuid(), request.lessonId))

    @PostMapping("/{id}/occurrence")
    fun updateSportSectionOccurrence(
        @PathVariable id: Long,
        @RequestBody request: SportSectionOccurrenceRequest,
        authentication: Authentication,
    ): ApiResponse<MySportSection> = ApiResponse.success(service.setSkipped(authentication.uuid(), id, request.start, request.skipped))

    @PostMapping("/{id}/stop")
    fun stopSportSection(@PathVariable id: Long, authentication: Authentication): ApiResponse<String> {
        service.stop(authentication.uuid(), id)
        return ApiResponse.success("Section stopped")
    }
}
