package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportTimeSlot
import org.springframework.data.jpa.repository.JpaRepository

interface SportTimeSlotRepository : JpaRepository<SportTimeSlot, Long> {
}