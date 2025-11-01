package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportNotificationFilter
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface SportNotificationFilterRepository : JpaRepository<SportNotificationFilter, Long> {
    @Query(
        """
        SELECT snf FROM SportNotificationFilter snf 
        LEFT JOIN FETCH snf.user
        LEFT JOIN FETCH snf.sections
        LEFT JOIN FETCH snf.buildings
        LEFT JOIN FETCH snf.teachers
        """
    )
    fun findAllWithDetails(): List<SportNotificationFilter>
}