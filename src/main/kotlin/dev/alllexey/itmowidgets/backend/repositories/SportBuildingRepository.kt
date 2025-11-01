package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportBuilding
import org.springframework.data.jpa.repository.JpaRepository

interface SportBuildingRepository : JpaRepository<SportBuilding, Long> {
}