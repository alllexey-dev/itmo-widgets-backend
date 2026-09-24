package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class UserRole { MODERATOR, ADMIN }

@Embeddable
data class UserRoleId(
    @Column(nullable = false) val userId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val role: UserRole,
) : java.io.Serializable

@Entity
@Table(name = "user_roles")
class UserRoleEntity(
    @EmbeddedId val id: UserRoleId,
    @Column(nullable = false) val grantedAt: Instant,
)
