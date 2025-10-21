package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.services.DeviceService
import dev.alllexey.itmowidgets.core.model.ApiResponse
import dev.alllexey.itmowidgets.core.model.RegisterDeviceRequest
import jakarta.validation.Valid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping("/api/device")
class DeviceController(private val deviceService: DeviceService) {

    @PostMapping("/register-device")
    fun registerDevice(
        @Valid @RequestBody request: RegisterDeviceRequest,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = UUID.fromString(authentication.name)
        deviceService.registerOrUpdateDevice(userId, request.fcmToken, request.deviceName)
        return ApiResponse.success("Device registered successfully.")
    }
}

