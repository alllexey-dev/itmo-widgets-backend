package me.alllexey123.itmowidgets.backend.controllers

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import me.alllexey123.itmowidgets.backend.services.DeviceService
import me.alllexey123.itmowidgets.backend.utils.ApiResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class RegisterDeviceRequest(
    @field:NotBlank val fcmToken: String,
    @field:NotBlank val deviceName: String
)

data class TestNotificationRequest(
    @field:NotBlank val title: String,
    @field:NotBlank val body: String
)

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

    @PostMapping("/send-test-to-self")
    fun sendTestNotification(
        @Valid @RequestBody request: TestNotificationRequest,
        authentication: Authentication
    ): ApiResponse<String> {
        val userId = UUID.fromString(authentication.name)
        deviceService.sendNotificationToUser(userId, request.title, request.body)
        return ApiResponse.success("Test notification sent.")
    }
}

