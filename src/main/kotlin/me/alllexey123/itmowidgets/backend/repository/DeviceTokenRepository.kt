package me.alllexey123.itmowidgets.backend.repository

import me.alllexey123.itmowidgets.backend.model.DeviceToken
import org.springframework.data.jpa.repository.JpaRepository

interface DeviceTokenRepository : JpaRepository<DeviceToken, String> {
}