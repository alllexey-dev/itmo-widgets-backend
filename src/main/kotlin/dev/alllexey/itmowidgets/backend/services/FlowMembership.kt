package dev.alllexey.itmowidgets.backend.services

import java.util.UUID

/** A schedule flow of one subject; lectures are shared by a whole stream, other types by a group. */
data class SubjectFlow(val flowId: Long, val groupName: String, val lecture: Boolean)

/** Which schedule flows of a subject in a period a user belongs to. */
interface FlowMembership {
    fun flowsOf(userId: UUID, subjectId: Long, periodKey: String): List<SubjectFlow>
    fun sharesAny(userId: UUID, subjectId: Long, periodKey: String, flowIds: Set<Long>): Boolean
}
