package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SubjectLinkSaveEntity
import dev.alllexey.itmowidgets.backend.model.SubjectLinkSaveId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface SubjectLinkSaveRepository : JpaRepository<SubjectLinkSaveEntity, SubjectLinkSaveId> {
    @Query("SELECT s.id.linkId FROM SubjectLinkSaveEntity s WHERE s.id.userId = :userId AND s.id.linkId IN :linkIds")
    fun findSavedLinkIds(userId: UUID, linkIds: Collection<UUID>): List<UUID>
}
