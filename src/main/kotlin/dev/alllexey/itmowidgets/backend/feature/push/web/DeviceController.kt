package dev.alllexey.itmowidgets.backend.feature.push.web

import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/device")
class DeviceController(private val deviceService: DeviceService) {

    @PostMapping("/register-device")
    fun registerDevice(
        @RequestBody request: RegisterDeviceRequest,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = authentication.uuid()
        deviceService.registerOrUpdateDevice(userId, request.fcmToken, request.deviceName)
        return ApiResponse.success("Device registered successfully.")
    }

    @DeleteMapping("/current")
    fun unregisterCurrentDevice(
        @RequestBody request: UnregisterDeviceRequest,
        authentication: Authentication
    ): ApiResponse<String> {
        deviceService.unregisterDevice(authentication.uuid(), request.fcmToken)
        return ApiResponse.success("Device unregistered successfully.")
    }
}

