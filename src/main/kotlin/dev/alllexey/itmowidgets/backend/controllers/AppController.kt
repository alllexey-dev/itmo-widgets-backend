package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.configs.AppConfig
import dev.alllexey.itmowidgets.core.model.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/app")
class AppController(private val appConfig: AppConfig) {

    @GetMapping("/version")
    fun appVersion(): ApiResponse<String> {
        return ApiResponse.success(appConfig.version)
    }
}