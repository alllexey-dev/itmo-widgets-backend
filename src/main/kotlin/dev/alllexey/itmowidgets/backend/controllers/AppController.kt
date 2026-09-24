package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.dto.AppVersionInfo
import dev.alllexey.itmowidgets.backend.services.AppVersionSettings
import dev.alllexey.itmowidgets.core.model.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/app")
class AppController(private val versions: AppVersionSettings) {

    @GetMapping("/version")
    fun appVersion(): ApiResponse<String> {
        return ApiResponse.success(versions.current().latest)
    }

    @GetMapping("/version-info")
    fun appVersionInfo(): ApiResponse<AppVersionInfo> {
        val version = versions.current()
        return ApiResponse.success(AppVersionInfo(minVersion = version.minimum, latestVersion = version.latest, note = version.note))
    }
}
