package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** One row per unordered pair; direction matters only while the request is pending. */
@Entity
@Table(name = "friendships")
class FriendshipEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false)
    val requester: User,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "addressee_id", nullable = false)
    val addressee: User,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: Status = Status.PENDING,
    @Column(nullable = false)
    val createdAt: Instant,
    @Column
    var respondedAt: Instant? = null,
) {
    enum class Status { PENDING, ACCEPTED }
}
