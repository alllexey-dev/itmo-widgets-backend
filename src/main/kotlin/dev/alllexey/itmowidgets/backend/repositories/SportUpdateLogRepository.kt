package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportUpdateLog
import org.springframework.data.jpa.repository.JpaRepository

interface SportUpdateLogRepository : JpaRepository<SportUpdateLog, Long> {
}