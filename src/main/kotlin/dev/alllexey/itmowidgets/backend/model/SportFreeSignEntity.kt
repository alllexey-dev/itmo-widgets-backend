package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant

enum class FreeSignEntryStatus {
    WAITING,
    SATISFIED,
    EXPIRED
}

@Entity
@Table(name = "sport_free_sign_entries")
class SportFreeSignEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id")
    val lesson: SportLesson,

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: FreeSignEntryStatus = FreeSignEntryStatus.WAITING
)