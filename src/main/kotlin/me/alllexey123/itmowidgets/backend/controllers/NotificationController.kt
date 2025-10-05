package me.alllexey123.itmowidgets.backend.controllers

import me.alllexey123.itmowidgets.backend.network.TokenRegistrationRequest
import me.alllexey123.itmowidgets.backend.service.DeviceTokenService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class NotificationController(
    private val deviceTokenService: DeviceTokenService
) {

    @PostMapping("/register-token")
    fun registerToken(@RequestBody request: TokenRegistrationRequest) {
        deviceTokenService.saveToken(request.token, request.userId)
    }
}