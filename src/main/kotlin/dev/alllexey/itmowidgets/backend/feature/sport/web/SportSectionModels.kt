package dev.alllexey.itmowidgets.backend.feature.sport.web

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionOccurrenceState
import java.time.OffsetDateTime

data class SportSectionRequest(val lessonId: Long)

/** Turns one predicted occurrence of a section off (`skipped = true`) or back on. */
data class SportSectionOccurrenceRequest(val start: OffsetDateTime, val skipped: Boolean)

/** The caller's own section: a slot of the same lesson every two weeks. */
data class MySportSection(
    val id: Long,
    /** The booked lesson the section was made from: the slot's section, teacher, place, weekday and time. */
    val lesson: SportLessonDto,
    /** The next predicted occurrences, earliest first. */
    val occurrences: List<SportSectionDate>,
    /** The caller's place in the slot's section queue, from 1. */
    val place: Int,
    /** Everyone in the slot's section queue, the caller included. */
    val subscribers: Int,
    val createdAt: OffsetDateTime,
)

data class SportSectionDate(
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    /** Null until auto-sign for this date opens (its prototype is published) and the user did not turn it off. */
    val state: SportSectionOccurrenceState?,
    /** The auto-sign entry of a queued occurrence. */
    val entryId: Long?,
)

/** A lesson's slot as the caller sees it: counts and the caller's own place, never other subscribers. */
data class SportSectionSlot(
    val lessonId: Long,
    val subscribers: Int,
    /** How many subscribers the slot admits; null while the lesson's places are unknown. */
    val cap: Int?,
    /** The caller cannot subscribe: the cap is reached. */
    val full: Boolean,
    /** The caller's own subscription to this slot. */
    val subscriptionId: Long?,
    val place: Int?,
)
