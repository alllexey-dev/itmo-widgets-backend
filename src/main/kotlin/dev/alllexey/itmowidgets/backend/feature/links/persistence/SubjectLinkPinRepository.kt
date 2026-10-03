package dev.alllexey.itmowidgets.backend.feature.links.persistence

import dev.alllexey.itmowidgets.backend.feature.links.model.SubjectLinkPinEntity
import dev.alllexey.itmowidgets.backend.feature.links.model.SubjectLinkPinId
import org.springframework.data.jpa.repository.JpaRepository

interface SubjectLinkPinRepository : JpaRepository<SubjectLinkPinEntity, SubjectLinkPinId>
