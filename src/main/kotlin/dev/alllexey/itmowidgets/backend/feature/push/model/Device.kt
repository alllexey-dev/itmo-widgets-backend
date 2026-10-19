package dev.alllexey.itmowidgets.backend.feature.push.model

import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "devices")
class Device(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    var user: User,

    /** Null for a device created from an `X-App-Version` report of a build without push ([PushProvider.NONE]). */
    @Column(unique = true)
    var fcmToken: String?,

    @Column
    var deviceName: String,

    @Column
    var lastLogin: Instant,

    @Column
    @Enumerated(EnumType.STRING)
    var platform: AppPlatform = AppPlatform.ANDROID,

    /** Whether the user allows alerts; only an [AppPlatform.IOS] device without them is skipped by deliveries. */
    @Column
    var alertsAllowed: Boolean = true,

    /**
     * The last `X-App-Version` this device sent; all five are null until the first report. A registration body
     * may set [appVersion] alone.
     */
    @Column
    var appVersion: String? = null,

    @Column
    var appBuild: Int? = null,

    @Column
    @Enumerated(EnumType.STRING)
    var appPlatform: AppPlatform? = null,

    @Column
    var appDistribution: String? = null,

    @Column
    var appVersionSeenAt: Instant? = null,

    @Column
    @Enumerated(EnumType.STRING)
    var pushProvider: PushProvider = PushProvider.FCM,

    /** The categories the user switched off on this device: their pushes skip it. */
    @Column
    @Convert(converter = PushCategorySetConverter::class)
    var mutedPushCategories: Set<PushCategory> = emptySet(),
) {
    fun usePushToken(token: String) {
        fcmToken = token
        pushProvider = PushProvider.FCM
    }

    fun reportClientVersion(reported: ClientVersion, seenAt: Instant) {
        appVersion = reported.version
        appBuild = reported.build
        appPlatform = reported.platform
        appDistribution = reported.distribution
        appVersionSeenAt = seenAt
    }
}
