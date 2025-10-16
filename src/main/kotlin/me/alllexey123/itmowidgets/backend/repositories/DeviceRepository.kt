package me.alllexey123.itmowidgets.backend.repositories

import me.alllexey123.itmowidgets.backend.model.Device
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface DeviceRepository : JpaRepository<Device, UUID> {

    fun findByFcmToken(fcmToken: String): Device?
}