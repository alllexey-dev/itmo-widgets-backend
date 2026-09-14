package dev.alllexey.itmowidgets.backend.model

import java.time.Instant
import java.time.OffsetDateTime

/**
 * The only definition of when a frozen forecast and a catalog lesson are the same lesson.
 * Both sides collapse into one comparable key, so persistence compares keys and never rebuilds
 * the predicate in JPQL. A rule change here cannot leave a query behind.
 */
object SportQueueRules {
    /** A prototype predicts the lesson exactly two weeks later; applied once, when a snapshot freezes. */
    const val PREDICTION_WEEKS = 2L

    /**
     * Null means this location can never prove an identity, so the row must not match anything.
     * Stored as NULL, where SQL equality already refuses to match, including against itself.
     */
    fun matchKey(
        sectionId: Long,
        teacherIsu: Long,
        buildingId: Long?,
        roomId: Long,
        sectionLevel: Long,
        lessonLevel: Long,
        typeId: Long,
        timeSlotId: Long,
        start: OffsetDateTime,
        end: OffsetDateTime,
    ): String? {
        val venue = venueKey(buildingId, roomId) ?: return null
        // Instants, never rendered offsets: one moment written as +03:00 and as Z is one key.
        return listOf(
            sectionId, teacherIsu, venue, roomId, sectionLevel, lessonLevel, typeId, timeSlotId,
            start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli(),
        ).joinToString("|")
    }

    fun matchKey(lesson: SportLesson): String? = matchKey(
        sectionId = lesson.section.id,
        teacherIsu = lesson.teacher.isu,
        buildingId = lesson.buildingId,
        roomId = lesson.roomId,
        sectionLevel = lesson.sectionLevel,
        lessonLevel = lesson.lessonLevel,
        typeId = lesson.typeId,
        timeSlotId = lesson.timeSlot.id,
        start = lesson.start,
        end = lesson.end,
    )

    fun matches(prediction: SportPredictionSnapshot, target: SportLesson): Boolean {
        val predicted = prediction.matchKey ?: return false
        return predicted == matchKey(target)
    }

    /** Unknown/off-site filter categories cannot prove a venue; room -1 explicitly denotes online. */
    private fun venueKey(buildingId: Long?, roomId: Long): String? = when {
        roomId == -1L && (buildingId == null || buildingId == -1L) -> "online"
        roomId > 0L && buildingId != null && buildingId > 0L -> "b$buildingId"
        else -> null
    }

    /** How long before a lesson starts a non-force free entry stops being eligible. */
    const val FREE_NON_FORCE_LEAD_HOURS = 1L

    /** Non-force stops one hour before start; force remains eligible strictly before lesson end. */
    fun freeDeadline(entry: SportFreeSignEntity): Instant = (
        if (entry.forceSign) entry.lesson.end
        else entry.lesson.start.minusHours(FREE_NON_FORCE_LEAD_HOURS)
    ).toInstant()

    /**
     * Widest start time an expired free entry of either kind can have, so discovery can select a
     * superset with one comparison instead of restating [freeDeadline]. Force entries expire at
     * lesson end, which is always later than their start, so this bound covers them too.
     */
    fun freeExpiryHorizon(now: OffsetDateTime): OffsetDateTime = now.plusHours(FREE_NON_FORCE_LEAD_HOURS)
}
