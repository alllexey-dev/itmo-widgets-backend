package dev.alllexey.itmowidgets.backend.services

import java.util.UUID

/**
 * A schedule flow of one subject. [depth] is the number of parts of the trailing flow number in
 * [groupName]: `ФИЗ ПИИКТ 3` is 1 (lectures), `3.2` is 2 (practice), `3.2.1` is 3 (labs); a name
 * without a number is 1.
 */
data class SubjectFlow(val flowId: Long, val groupName: String, val typeId: Int, val depth: Int) {
    companion object {
        private val FLOW_NUMBER = Regex("""(?:^|\s)(\d+(?:\.\d+)*)$""")

        fun depthOf(groupName: String): Int =
            FLOW_NUMBER.find(groupName.trim())?.groupValues?.get(1)?.split('.')?.size ?: 1
    }
}

/** Which schedule flows of a subject in a period a user belongs to. */
interface FlowMembership {
    fun flowsOf(userId: UUID, subjectId: Long, periodKey: String): List<SubjectFlow>
    fun isMember(userId: UUID, subjectId: Long, periodKey: String, flowId: Long): Boolean
}
