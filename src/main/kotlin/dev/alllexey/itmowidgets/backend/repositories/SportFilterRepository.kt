package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.SportFilter
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface SportFilterRepository : JpaRepository<SportFilter, Long> {
    @Query(
        """
        SELECT sf FROM SportFilter sf 
        LEFT JOIN FETCH sf.user
        LEFT JOIN FETCH sf.sections
        LEFT JOIN FETCH sf.buildings
        LEFT JOIN FETCH sf.teachers
        """
    )
    fun findAllWithDetails(): List<SportFilter>

    @Query(
        """
        SELECT sf FROM SportFilter sf 
        LEFT JOIN FETCH sf.sections
        LEFT JOIN FETCH sf.buildings
        LEFT JOIN FETCH sf.teachers
        LEFT JOIN FETCH sf.timeSlots
        WHERE sf.user.id = :userId
        """
    )
    fun findAllByUserIdWithDetails(userId: UUID): List<SportFilter>
}