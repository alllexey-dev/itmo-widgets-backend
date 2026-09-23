package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SubjectLinkSaveEntity
import dev.alllexey.itmowidgets.backend.model.SubjectLinkSaveId
import org.springframework.data.jpa.repository.JpaRepository

interface SubjectLinkSaveRepository : JpaRepository<SubjectLinkSaveEntity, SubjectLinkSaveId>
