package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SubjectLinkAudienceEntity
import dev.alllexey.itmowidgets.backend.model.SubjectLinkAudienceId
import org.springframework.data.jpa.repository.JpaRepository

interface SubjectLinkAudienceRepository : JpaRepository<SubjectLinkAudienceEntity, SubjectLinkAudienceId>
