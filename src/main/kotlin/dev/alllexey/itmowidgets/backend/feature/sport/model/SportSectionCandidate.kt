package dev.alllexey.itmowidgets.backend.feature.sport.model

import java.util.UUID

/** Discovery results deliberately contain no managed JPA entities. */
data class SportSectionCandidate(val subscriptionId: Long, val userId: UUID, val slotKey: String)

/** One subscription's pass: the published lessons of its slot, earliest first, each the prototype of an occurrence. */
data class SportSectionStep(val subscriptionId: Long, val userId: UUID, val prototypeLessonIds: List<Long>)
