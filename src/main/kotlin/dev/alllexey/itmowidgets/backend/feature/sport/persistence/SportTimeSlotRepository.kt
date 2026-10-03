package dev.alllexey.itmowidgets.backend.feature.sport.persistence

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportTimeSlot
import org.springframework.data.jpa.repository.JpaRepository

interface SportTimeSlotRepository : JpaRepository<SportTimeSlot, Long> {
}