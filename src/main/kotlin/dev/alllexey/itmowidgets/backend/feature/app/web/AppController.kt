package dev.alllexey.itmowidgets.backend.feature.app.web

import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.app.service.AppVersionSettings
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/app")
class AppController(private val versions: AppVersionSettings) {

    /** The latest Android version, kept for 2.0.x clients. */
    @GetMapping("/version")
    fun appVersion(): ApiResponse<String> = ApiResponse.success(versions.current(AppPlatform.ANDROID).latest)

    /**
     * Without `platform` the Android metadata, as released 2.1 and 2.2 clients and the deploy smoke check read it.
     * An unknown `platform` is a 400 `invalid_request` by contract: Web detects this route's support by probing it.
     */
    @GetMapping("/version-info")
    fun appVersionInfo(@RequestParam(defaultValue = "ANDROID") platform: AppPlatform): ApiResponse<AppVersionInfo> {
        val version = versions.current(platform)
        return ApiResponse.success(AppVersionInfo(minVersion = version.minimum, latestVersion = version.latest, note = version.note))
    }
}
