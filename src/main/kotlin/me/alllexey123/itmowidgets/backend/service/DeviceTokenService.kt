package me.alllexey123.itmowidgets.backend.service

import me.alllexey123.itmowidgets.backend.model.DeviceToken
import me.alllexey123.itmowidgets.backend.repository.DeviceTokenRepository
import org.springframework.stereotype.Service

@Service
class DeviceTokenService(private val deviceTokenRepository: DeviceTokenRepository) {

    fun saveToken(token: String, userId: String) : DeviceToken {
        val deviceToken = DeviceToken(userId, token)
        return deviceTokenRepository.save(deviceToken)
    }

    
}