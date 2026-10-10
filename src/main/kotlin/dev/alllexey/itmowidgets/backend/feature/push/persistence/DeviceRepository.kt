package dev.alllexey.itmowidgets.backend.feature.push.persistence

import dev.alllexey.itmowidgets.backend.feature.admin.persistence.LabelCount
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDevice
import dev.alllexey.itmowidgets.backend.feature.push.model.Device
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.*

/** JPQL: every device but an iOS device without alerts allowed. */
private const val DELIVERABLE =
    "(d.alertsAllowed = true OR d.platform <> dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform.IOS)"

interface DeviceRepository : JpaRepository<Device, UUID> {

    fun findByFcmToken(fcmToken: String): Device?

    /** The devices of [userId] a push can reach: every one but an iOS device whose user turned alerts off. */
    @Query("SELECT d FROM Device d WHERE d.user.id = :userId AND $DELIVERABLE")
    fun findDeliverableByUserId(userId: UUID): List<Device>

    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END FROM Device d WHERE d.user.id = :userId AND $DELIVERABLE")
    fun existsDeliverableByUserId(userId: UUID): Boolean

    /** Registered devices by `platform`, one row per platform that has any. */
    @Query(nativeQuery = true, value = "SELECT platform AS label, COUNT(*) AS total FROM devices GROUP BY platform")
    fun countByPlatform(): List<LabelCount>

    @Modifying
    @Query("DELETE FROM Device d WHERE d.id = :deviceId AND d.fcmToken = :fcmToken")
    fun deleteIfTokenMatches(deviceId: UUID, fcmToken: String): Int

    @Query(
        """
        SELECT new dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDevice(
            d.deviceName, d.lastLogin, d.appVersion, d.appBuild, d.appPlatform, d.appDistribution, d.appVersionSeenAt,
            d.platform
        )
        FROM Device d WHERE d.user.id = :userId ORDER BY d.lastLogin DESC
        """,
    )
    fun findAdminDevices(userId: UUID): List<AdminDevice>

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

    /**
     * Stores a reported build on the one device of [userId] it can belong to: a device that last reported
     * [platform], or for Android also one that never reported (every device registered before the header existed
     * is an Android one). With no such device, or with two, nothing is written. A device that already holds the
     * same build seen after [staleBefore] is not rewritten. Returns the number of updated rows, 0 or 1.
     */
    @Transactional
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        UPDATE devices d SET app_version = :version, app_build = :build, app_platform = :platform,
            app_distribution = :distribution, app_version_seen_at = :seenAt
        WHERE d.user_id = :userId
          AND (d.app_platform = :platform OR (d.app_platform IS NULL AND :platform = 'ANDROID'))
          AND (
              SELECT COUNT(*) FROM devices c
              WHERE c.user_id = :userId AND (c.app_platform = :platform OR (c.app_platform IS NULL AND :platform = 'ANDROID'))
          ) = 1
          AND (
              d.app_version_seen_at IS NULL OR d.app_version_seen_at <= :staleBefore
              OR d.app_version IS DISTINCT FROM :version OR d.app_build IS DISTINCT FROM :build
              OR d.app_distribution IS DISTINCT FROM :distribution
          )
        """,
    )
    fun recordClientVersion(
        userId: UUID,
        version: String,
        build: Int,
        platform: String,
        distribution: String,
        seenAt: Instant,
        staleBefore: Instant,
    ): Int

    /**
     * Devices active since [since], by the build they last reported. A device is active when it registered or
     * reported a build since then; devices that never reported form the row whose columns are all null.
     */
    @Query(
        nativeQuery = true,
        value = """
        SELECT app_platform AS platform, app_distribution AS distribution, app_version AS version, app_build AS build,
            COUNT(*) AS devices
        FROM devices
        WHERE GREATEST(last_login, app_version_seen_at) >= :since
        GROUP BY app_platform, app_distribution, app_version, app_build
        """,
    )
    fun countActiveByClientBuild(since: Instant): List<ClientBuildCount>
}
