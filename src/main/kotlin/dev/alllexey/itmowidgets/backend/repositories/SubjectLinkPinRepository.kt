package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SubjectLinkPinEntity
import dev.alllexey.itmowidgets.backend.model.SubjectLinkPinId
import org.springframework.data.jpa.repository.JpaRepository

interface SubjectLinkPinRepository : JpaRepository<SubjectLinkPinEntity, SubjectLinkPinId>
