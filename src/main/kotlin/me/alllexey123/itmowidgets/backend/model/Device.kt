package me.alllexey123.itmowidgets.backend.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
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
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    @Column(unique = true, nullable = false)
    var fcmToken: String,

    @Column(nullable = false)
    var deviceName: String,

    @Column(nullable = false)
    var lastLogin: Instant = Instant.now()
)