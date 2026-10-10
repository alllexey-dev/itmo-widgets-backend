package dev.alllexey.itmowidgets.backend.feature.push.model

import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import jakarta.persistence.Column
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

    @Column(unique = true)
    var fcmToken: String,

    @Column
    var deviceName: String,

    @Column
    var lastLogin: Instant = Instant.now(),

    /** The last `X-App-Version` this device sent; all five are null until the first report. */
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
) {
    fun reportClientVersion(reported: ClientVersion, seenAt: Instant) {
        appVersion = reported.version
        appBuild = reported.build
        appPlatform = reported.platform
        appDistribution = reported.distribution
        appVersionSeenAt = seenAt
    }
}
