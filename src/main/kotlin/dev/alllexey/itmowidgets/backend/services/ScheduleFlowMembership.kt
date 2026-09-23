package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.repositories.UserSubjectFlowRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Trusts the schedule the user uploaded; flows stay after their lessons are removed. */
@Service
class ScheduleFlowMembership(private val flows: UserSubjectFlowRepository) : FlowMembership {
    @Transactional(readOnly = true)
    override fun flowsOf(userId: UUID, subjectId: Long, periodKey: String): List<SubjectFlow> =
        flows.findByUserAndScope(userId, subjectId, periodKey).map {
            SubjectFlow(it.id.flowId, it.groupName, lecture = it.typeId == LECTURE_TYPE_ID)
        }

    @Transactional(readOnly = true)
    override fun sharesAny(userId: UUID, subjectId: Long, periodKey: String, flowIds: Set<Long>): Boolean =
        flowIds.isNotEmpty() && flows.findByUserAndScope(userId, subjectId, periodKey).any { it.id.flowId in flowIds }

    private companion object {
        const val LECTURE_TYPE_ID = 1
    }
}
