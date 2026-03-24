package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "friend_requests",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["from_user_id", "to_user_id"])
    ]
)
class FriendRequestEntity(

    @Id
    var id: UUID = UUID.randomUUID(),

    @ManyToOne
    @JoinColumn(name = "from_user_id")
    val from: User,

    @ManyToOne
    @JoinColumn(name = "to_user_id")
    val to: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: Status = Status.ACTIVE,

    @Column
    val createdAt: Instant = Instant.now(),

    @Column
    var lastActivatedAt: Instant = Instant.now(),
) {
    enum class Status {
        ACTIVE,
        CANCELLED
    }
}