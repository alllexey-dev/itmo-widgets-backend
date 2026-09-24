package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.UserSubjectFlowId
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
            SubjectFlow(it.id.flowId, it.groupName, it.typeId, SubjectFlow.depthOf(it.groupName))
        }

    @Transactional(readOnly = true)
    override fun isMember(userId: UUID, subjectId: Long, periodKey: String, flowId: Long): Boolean =
        flows.existsById(UserSubjectFlowId(userId, subjectId, periodKey, flowId))
}
