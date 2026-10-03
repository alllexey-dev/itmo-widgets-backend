package dev.alllexey.itmowidgets.backend.feature.sport.persistence

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSection
import org.springframework.data.jpa.repository.JpaRepository

interface SportSectionRepository : JpaRepository<SportSection, Long>
