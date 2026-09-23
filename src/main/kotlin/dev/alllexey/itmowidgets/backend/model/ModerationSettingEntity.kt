package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "moderation_settings")
class ModerationSettingEntity(
    @Id @Column(length = 64) val key: String,
    @Column(nullable = false, length = 200) val value: String,
    @Column(nullable = false) val updatedAt: Instant,
    @Column(nullable = false) val updatedBy: UUID,
)
