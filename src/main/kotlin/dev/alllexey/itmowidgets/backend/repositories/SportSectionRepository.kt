package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportSection
import org.springframework.data.jpa.repository.JpaRepository

interface SportSectionRepository : JpaRepository<SportSection, Long> {
}