package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SubjectLinkAudienceEntity
import dev.alllexey.itmowidgets.backend.model.SubjectLinkAudienceId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface SubjectLinkAudienceRepository : JpaRepository<SubjectLinkAudienceEntity, SubjectLinkAudienceId> {
    @Query("SELECT a FROM SubjectLinkAudienceEntity a WHERE a.id.linkId IN :linkIds")
    fun findAllByLinkIds(linkIds: Collection<UUID>): List<SubjectLinkAudienceEntity>
}
