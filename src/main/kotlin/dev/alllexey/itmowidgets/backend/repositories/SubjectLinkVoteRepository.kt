package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SubjectLinkVoteEntity
import dev.alllexey.itmowidgets.backend.model.SubjectLinkVoteId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface SubjectLinkVoteRepository : JpaRepository<SubjectLinkVoteEntity, SubjectLinkVoteId> {
    @Query("SELECT COALESCE(SUM(v.value), 0) FROM SubjectLinkVoteEntity v WHERE v.id.linkId = :linkId")
    fun sumValues(linkId: UUID): Int

    @Query("SELECT v FROM SubjectLinkVoteEntity v WHERE v.id.userId = :userId AND v.id.linkId IN :linkIds")
    fun findByUserAndLinks(userId: UUID, linkIds: Collection<UUID>): List<SubjectLinkVoteEntity>
}
