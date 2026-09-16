package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.Device
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.*

interface DeviceRepository : JpaRepository<Device, UUID> {

    fun findByFcmToken(fcmToken: String): Device?

    fun findByUserId(userId: UUID): List<Device>

    fun existsByUserId(userId: UUID): Boolean

    @Modifying
    @Query("DELETE FROM Device d WHERE d.id = :deviceId AND d.fcmToken = :fcmToken")
    fun deleteIfTokenMatches(deviceId: UUID, fcmToken: String): Int
}
