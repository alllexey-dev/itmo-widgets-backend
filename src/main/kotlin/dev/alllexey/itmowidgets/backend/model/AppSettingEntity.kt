package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "app_settings")
class AppSettingEntity(
    @Id @Column(length = 64) val key: String,
    @Column(nullable = false, length = 500) val value: String,
    @Column(nullable = false) val updatedAt: Instant,
    val updatedBy: UUID?,
)
