package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "sport_free_sign_entries")
class SportFreeSignEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id")
    val lesson: SportLesson,

    @Enumerated(EnumType.STRING)
    var status: QueueEntryStatus = QueueEntryStatus.WAITING,

    var isCancelled: Boolean = false,

    val createdAt: Instant = Instant.now(),

    var firstNotifiedAt: Instant? = null,

    var lastNotifiedAt: Instant? = null,

    var cancelledAt: Instant? = null,

    var satisfiedAt: Instant? = null,

    var expiredAt: Instant? = null,

    val forceSign: Boolean,

    var notificationAttempts: Int = 0,

    var maxNotificationAttempts: Int = 10,
)