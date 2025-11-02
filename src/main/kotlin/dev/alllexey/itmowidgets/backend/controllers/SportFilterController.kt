package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.services.SportFilterService
import dev.alllexey.itmowidgets.core.model.ApiResponse
import dev.alllexey.itmowidgets.core.model.SportFilterRequest
import dev.alllexey.itmowidgets.core.model.SportFilterResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping("/api/sport/filter")
class SportFilterController(private val sportFilterService: SportFilterService) {

    @PostMapping("/create")
    fun createSportFilter(
        authentication: Authentication,
        @RequestBody request: SportFilterRequest
    ): ApiResponse<SportFilterResponse> {
        val userId = UUID.fromString(authentication.name)
        val result = sportFilterService.createFilterForUser(userId, request)
        return ApiResponse.success(sportFilterService.toResponse(result))
    }

    @PostMapping("/{id}")
    fun editSportFilter(
        authentication: Authentication,
        @RequestBody request: SportFilterRequest,
        @PathVariable id: Long
    ): ApiResponse<SportFilterResponse> {
        val userId = UUID.fromString(authentication.name)
        val result = sportFilterService.editFilterForUser(userId, id, request)
        return ApiResponse.success(sportFilterService.toResponse(result))
    }

    @GetMapping("/all")
    fun allSportFilters(authentication: Authentication): ApiResponse<List<SportFilterResponse>> {
        val userId = UUID.fromString(authentication.name)
        val results = sportFilterService.findAllForUser(userId)
        val response = results.map { sportFilterService.toResponse(it) }
        return ApiResponse.success(response)
    }

    @DeleteMapping("/{id}")
    fun deleteSportFilter(
        authentication: Authentication,
        @PathVariable id: Long
    ): ApiResponse<String> {
        val userId = UUID.fromString(authentication.name)
        sportFilterService.deleteFilterForUser(userId, id)
        return ApiResponse.success("Filter deleted successfully.")
    }

    @DeleteMapping("/all")
    fun deleteAllSportFilters(authentication: Authentication): ApiResponse<String> {
        val userId = UUID.fromString(authentication.name)
        sportFilterService.deleteAllFiltersForUser(userId)
        return ApiResponse.success("All filters deleted successfully.")
    }

    @GetMapping("/allowed-building-ids")
    fun allowedBuildingIds(): ApiResponse<List<Long>> {
        val ids = sportFilterService.getAllowedBuildingIds()
        return ApiResponse.success(ids)
    }
}