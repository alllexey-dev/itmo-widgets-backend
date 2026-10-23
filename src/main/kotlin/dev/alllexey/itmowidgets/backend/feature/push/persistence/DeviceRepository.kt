package dev.alllexey.itmowidgets.backend.feature.push.persistence

import dev.alllexey.itmowidgets.backend.feature.admin.persistence.LabelCount
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDevice
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.push.model.Device
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.*

/** JPQL: every device with a push token but an iOS device without alerts allowed. */
private const val DELIVERABLE =
    "d.fcmToken IS NOT NULL AND " +
        "(d.alertsAllowed = true OR d.platform <> dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform.IOS)"

/** SQL: the device a report of `:platform` names, the platform it last reported or else the one it registered with. */
private const val OF_REPORTED_PLATFORM = "COALESCE(app_platform, platform) = :platform"

interface DeviceRepository : JpaRepository<Device, UUID> {

    fun findByFcmToken(fcmToken: String): Device?

    /** The user's device of [platform] that has no push token yet; at most one (`uq_devices_user_platform_without_push`). */
    fun findByUserIdAndPlatformAndFcmTokenIsNull(userId: UUID, platform: AppPlatform): Device?

    /** The user's devices registered by an app install that named itself in `X-Client`. */
    @Query("SELECT d FROM Device d WHERE d.user.id = :userId AND d.installId IS NOT NULL")
    fun findWithInstall(userId: UUID): List<Device>

    @Query("SELECT d FROM Device d WHERE d.user.id = :userId AND d.installId = :installId")
    fun findByInstall(userId: UUID, installId: UUID): List<Device>

    @Transactional
    @Modifying
    @Query("DELETE FROM Device d WHERE d.user.id = :userId AND d.installId = :installId")
    fun deleteByInstall(userId: UUID, installId: UUID): Int

    /** The devices of [userId] a push can reach: every one with a token but an iOS device whose user turned alerts off. */
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
     * [platform], or that never reported and registered with it (every device registered before the header existed
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
        WHERE d.user_id = :userId AND $OF_REPORTED_PLATFORM
          AND (SELECT COUNT(*) FROM devices WHERE user_id = :userId AND $OF_REPORTED_PLATFORM) = 1
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
     * Creates the iOS device of [userId] from its first reported build when the user has no iOS device: a build
     * without push never registers. The row has no push token and no alerts, so no delivery picks it; a later
     * registration with a token takes it over. Returns the number of inserted rows, 0 or 1.
     */
    @Transactional
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        INSERT INTO devices (id, user_id, fcm_token, push_provider, device_name, last_login, created_at, platform,
            alerts_allowed, app_version, app_build, app_platform, app_distribution, app_version_seen_at)
        SELECT :id, :userId, NULL, 'NONE', :deviceName, :seenAt, :seenAt, 'IOS', false, :version, :build, 'IOS',
            :distribution, :seenAt
        WHERE NOT EXISTS (SELECT 1 FROM devices WHERE user_id = :userId AND COALESCE(app_platform, platform) = 'IOS')
        ON CONFLICT DO NOTHING
        """,
    )
    fun insertIosDeviceWithoutPush(
        id: UUID,
        userId: UUID,
        deviceName: String,
        version: String,
        build: Int,
        distribution: String,
        seenAt: Instant,
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
