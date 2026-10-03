package dev.alllexey.itmowidgets.backend.feature.schedule.persistence

import dev.alllexey.itmowidgets.backend.feature.schedule.model.UserSubjectFlowEntity
import dev.alllexey.itmowidgets.backend.feature.schedule.model.UserSubjectFlowId
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.util.UUID

interface UserSubjectFlowRepository : JpaRepository<UserSubjectFlowEntity, UserSubjectFlowId> {
    @Query("""
        SELECT f FROM UserSubjectFlowEntity f
        WHERE f.id.userId = :userId AND f.id.subjectId = :subjectId AND f.id.periodKey = :periodKey
        ORDER BY f.id.flowId
        """)
    fun findByUserAndScope(userId: UUID, subjectId: Long, periodKey: String): List<UserSubjectFlowEntity>

    /** Distinct flows of the user, the most recently seen first. */
    @Query(value = """
        SELECT flow_id FROM user_subject_flows
        WHERE user_id = :userId
        GROUP BY flow_id
        ORDER BY MAX(last_seen) DESC, flow_id
        """, nativeQuery = true)
    fun findRecentFlowIds(userId: UUID, limit: Limit): List<Long>

    /** A narrower later sync never moves `last_seen` back. */
    @Modifying
    @Query(value = """
        INSERT INTO user_subject_flows (user_id, subject_id, period_key, flow_id, group_name, type_id, last_seen)
        VALUES (:userId, :subjectId, :periodKey, :flowId, :groupName, :typeId, :lastSeen)
        ON CONFLICT (user_id, subject_id, period_key, flow_id) DO UPDATE SET
            group_name = EXCLUDED.group_name,
            type_id = EXCLUDED.type_id,
            last_seen = GREATEST(user_subject_flows.last_seen, EXCLUDED.last_seen)
        """, nativeQuery = true)
    fun upsert(userId: UUID, subjectId: Long, periodKey: String, flowId: Long, groupName: String, typeId: Int, lastSeen: LocalDate): Int
}
