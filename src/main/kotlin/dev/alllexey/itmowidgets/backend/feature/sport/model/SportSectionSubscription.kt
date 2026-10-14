package dev.alllexey.itmowidgets.backend.feature.sport.model

import dev.alllexey.itmowidgets.backend.feature.users.model.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.time.OffsetDateTime

/** Stored values: constants are never renamed. */
enum class SportSectionStatus { ACTIVE, STOPPED }

enum class SportSectionStopReason { USER, EXPIRED }

/** A handled occurrence: queued, skipped for want of a free slot in the monthly limit, or turned off by the user. */
enum class SportSectionOccurrenceState { QUEUED, LIMIT, SKIPPED }

/** A user's section: the slot of [sourceLesson] ([SportQueueRules.slotKey]), the same lesson every two weeks. */
@Entity
@Table(name = "sport_section_subscriptions")
class SportSectionSubscription(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false)
    val user: User,

    @Column(name = "slot_key", nullable = false, updatable = false)
    val slotKey: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_lesson_id", updatable = false)
    val sourceLesson: SportLesson,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Column(name = "last_opened_ends_at", nullable = false)
    var lastOpenedEnd: OffsetDateTime = sourceLesson.end
        protected set

    @Enumerated(EnumType.STRING)
    var status: SportSectionStatus = SportSectionStatus.ACTIVE
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "stop_reason")
    var stopReason: SportSectionStopReason? = null
        protected set

    @Column(name = "stopped_at")
    var stoppedAt: Instant? = null
        protected set

    val isActive: Boolean get() = status == SportSectionStatus.ACTIVE

    /** The slot published the prototype of an occurrence ending at [end]: it is still in the timetable. */
    fun opened(end: OffsetDateTime) {
        if (end.isAfter(lastOpenedEnd)) lastOpenedEnd = end
    }

    fun stop(reason: SportSectionStopReason, now: Instant) {
        if (!isActive) return
        status = SportSectionStatus.STOPPED
        stopReason = reason
        stoppedAt = now
    }
}

/** One occurrence of a section, handled once: the unique (subscription, start) row is the idempotency key. */
@Entity
@Table(name = "sport_section_occurrences")
class SportSectionOccurrence(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", updatable = false)
    val subscription: SportSectionSubscription,

    @Column(name = "starts_at", nullable = false, updatable = false)
    val start: OffsetDateTime,

    @Column(name = "ends_at", nullable = false, updatable = false)
    val end: OffsetDateTime,

    @Enumerated(EnumType.STRING)
    var state: SportSectionOccurrenceState,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_id")
    var entry: SportAutoSignEntity? = null,
)
