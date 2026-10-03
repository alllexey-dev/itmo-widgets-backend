package dev.alllexey.itmowidgets.backend.feature.sport.persistence

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportBuilding
import org.springframework.data.jpa.repository.JpaRepository

interface SportBuildingRepository : JpaRepository<SportBuilding, Long> {
}