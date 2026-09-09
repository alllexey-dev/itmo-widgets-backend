package dev.alllexey.itmowidgets.backend.dto

import java.util.UUID

/** Discovery results deliberately contain no managed JPA entities. */
data class SportQueueCandidate(val entryId: Long, val userId: UUID)
