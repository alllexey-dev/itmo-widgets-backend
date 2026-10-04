package dev.alllexey.itmowidgets.backend.feature.sport.web

import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.time.OffsetDateTime

/** Also the stored `status` value: constants are never renamed, and no new value reaches released clients. */
enum class QueueEntryStatus {
    WAITING,
    NOTIFIED,
    GAVE_UP_NOTIFYING,
    SATISFIED,
    EXPIRED,
}

data class SportLessonDto(
    val id: Long,
    val sectionId: Long,
    val sectionName: String,
    /** 1 free attendance, 2 sections with selection. */
    val sectionLevel: Long,
    /** 1 free attendance, 2 section (training), 3 section (intermediate), 4 section (team). */
    val level: Long,
    /** For level 1: 1 open lesson, 2 free attendance, 5 debt, 6 standards, 7 external, 8 additional. */
    val typeId: Long,
    /** Raw venue ID, not a filter category; null is valid for online lessons. */
    val buildingId: Long?,
    val roomName: String,
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    val timeSlotId: Long,
    val teacherIsu: Long,
    val teacherFio: String,
)

/**
 * Clients tell the kinds apart by the `type` property ("free" or "auto"), a closed set for released apps. `@Schema`
 * only describes it for docs/openapi.json; `@JsonTypeInfo` would write a second `type` key.
 */
@Schema(
    oneOf = [SportFreeSignEntry::class, SportAutoSignEntry::class],
    discriminatorProperty = "type",
    discriminatorMapping = [
        DiscriminatorMapping(value = "free", schema = SportFreeSignEntry::class),
        DiscriminatorMapping(value = "auto", schema = SportAutoSignEntry::class),
    ],
)
sealed interface SportQueueEntry {
    val type: String

    val id: Long
    val position: Int
    val total: Int
    val isCancelled: Boolean
    val status: QueueEntryStatus
    val createdAt: OffsetDateTime
    val firstNotifiedAt: OffsetDateTime?
    val lastNotifiedAt: OffsetDateTime?
    val cancelledAt: OffsetDateTime?
    val satisfiedAt: OffsetDateTime?
    val expiredAt: OffsetDateTime?
    val notificationAttempts: Int
    val maxNotificationAttempts: Int

    /** The real lesson of a free-sign entry, the prototype lesson of an auto-sign entry. */
    val targetLesson: SportLessonDto
}

data class SportFreeSignEntry(
    override val id: Long,
    val lessonId: Long,
    override val position: Int,
    override val total: Int,
    override val isCancelled: Boolean,
    override val status: QueueEntryStatus,
    override val createdAt: OffsetDateTime,
    override val firstNotifiedAt: OffsetDateTime?,
    override val lastNotifiedAt: OffsetDateTime?,
    override val cancelledAt: OffsetDateTime?,
    override val satisfiedAt: OffsetDateTime?,
    override val expiredAt: OffsetDateTime?,
    override val notificationAttempts: Int,
    override val maxNotificationAttempts: Int,
    override val targetLesson: SportLessonDto,
    val forceSign: Boolean,
) : SportQueueEntry {
    @get:Schema(allowableValues = ["free"], requiredMode = Schema.RequiredMode.REQUIRED)
    override val type: String = "free"
}

data class SportAutoSignEntry(
    override val id: Long,
    val prototypeLessonId: Long,
    val realLessonId: Long?,
    override val position: Int,
    override val total: Int,
    override val isCancelled: Boolean,
    override val status: QueueEntryStatus,
    override val createdAt: OffsetDateTime,
    override val firstNotifiedAt: OffsetDateTime?,
    override val lastNotifiedAt: OffsetDateTime?,
    override val cancelledAt: OffsetDateTime?,
    override val satisfiedAt: OffsetDateTime?,
    override val expiredAt: OffsetDateTime?,
    override val notificationAttempts: Int,
    override val maxNotificationAttempts: Int,
    override val targetLesson: SportLessonDto,
    val realLesson: SportLessonDto?,
) : SportQueueEntry {
    @get:Schema(allowableValues = ["auto"], requiredMode = Schema.RequiredMode.REQUIRED)
    override val type: String = "auto"
}

/** The same closed `type` set as [SportQueueEntry]. */
@Schema(
    oneOf = [SportFreeSignQueue::class, SportAutoSignQueue::class],
    discriminatorProperty = "type",
    discriminatorMapping = [
        DiscriminatorMapping(value = "free", schema = SportFreeSignQueue::class),
        DiscriminatorMapping(value = "auto", schema = SportAutoSignQueue::class),
    ],
)
sealed interface SportQueue {
    val type: String
    val lessonId: Long
    val total: Int
}

/** Also built by a JPQL constructor expression in `SportFreeSignEntryRepository`. */
data class SportFreeSignQueue(override val lessonId: Long, override val total: Int) : SportQueue {
    @get:Schema(allowableValues = ["free"], requiredMode = Schema.RequiredMode.REQUIRED)
    override val type: String = "free"
}

/** Also built by a JPQL constructor expression in `SportAutoSignEntryRepository`. */
data class SportAutoSignQueue(override val lessonId: Long, override val total: Int, val realLessonId: Long?) : SportQueue {
    @get:Schema(allowableValues = ["auto"], requiredMode = Schema.RequiredMode.REQUIRED)
    override val type: String = "auto"
}

data class SportAutoSignLimits(val limit: Int, val available: Int, val nextAvailableAt: OffsetDateTime)

data class FriendSportBooking(
    val isu: Int,
    val lessonId: Long,
    /** Null when the friend is already signed up. */
    val entry: SportQueueEntry?,
)

data class FriendsSportBookingsResponse(val bookings: List<FriendSportBooking>)
