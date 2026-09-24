package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.Device
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.*

interface DeviceRepository : JpaRepository<Device, UUID> {

    fun findByFcmToken(fcmToken: String): Device?

    fun findByUserId(userId: UUID): List<Device>

    fun existsByUserId(userId: UUID): Boolean

    @Modifying
    @Query("DELETE FROM Device d WHERE d.id = :deviceId AND d.fcmToken = :fcmToken")
    fun deleteIfTokenMatches(deviceId: UUID, fcmToken: String): Int

    @Query("SELECT new dev.alllexey.itmowidgets.backend.dto.AdminDevice(d.deviceName, d.lastLogin) FROM Device d WHERE d.user.id = :userId ORDER BY d.lastLogin DESC")
    fun findAdminDevices(userId: UUID): List<dev.alllexey.itmowidgets.backend.dto.AdminDevice>

    @Query("SELECT MAX(d.lastLogin) FROM Device d WHERE d.user.id = :userId")
    fun findLastLogin(userId: UUID): Instant?

    fun countByLastLoginGreaterThanEqual(since: Instant): Long

    /** Devices by the calendar day in [zone] of their latest login, since [from]. */
    @Query(
        nativeQuery = true,
        value = """
        SELECT to_char(CAST(last_login AT TIME ZONE :zone AS date), 'YYYY-MM-DD') AS label, COUNT(*) AS total
        FROM devices WHERE last_login >= :from GROUP BY 1
        """,
    )
    fun countLastLoginPerDay(from: Instant, zone: String): List<LabelCount>
}
