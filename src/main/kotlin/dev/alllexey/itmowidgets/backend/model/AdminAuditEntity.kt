package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** Insert-only record of an admin change. */
@Entity
@Table(name = "admin_audit")
class AdminAuditEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val actorId: UUID,
    @Column(nullable = false, length = 40) val action: String,
    @Column(nullable = false, length = 200) val target: String,
    @Column(length = 1000) val details: String?,
    @Column(nullable = false) val createdAt: Instant,
)
