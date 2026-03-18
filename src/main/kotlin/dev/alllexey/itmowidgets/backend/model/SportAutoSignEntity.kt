package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "sport_auto_sign_entries")
class SportAutoSignEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prototype_lesson_id")
    val prototypeLesson: SportLesson,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "real_lesson_id")
    var realLesson: SportLesson?,

    @Enumerated(EnumType.STRING)
    var status: QueueEntryStatus = QueueEntryStatus.WAITING,

    var isCancelled: Boolean = false,

    val createdAt: Instant = Instant.now(),

    var firstNotifiedAt: Instant? = null,

    var lastNotifiedAt: Instant? = null,

    var cancelledAt: Instant? = null,

    var satisfiedAt: Instant? = null,

    var expiredAt: Instant? = null,

    var maxNotificationAttempts: Int = 10,

    var notificationAttempts: Int = 0,
)