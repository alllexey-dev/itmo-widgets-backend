package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.LessonEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
interface LessonRepository : JpaRepository<LessonEntity, UUID> {

    @Query("""
        SELECT *
            FROM lessons
            WHERE user_isu = :isu
              AND date BETWEEN :start AND :end
            ORDER BY date, start
        """,
        nativeQuery = true)
    fun findAllByIsuAndDates(isu: Int, start: LocalDate, end: LocalDate): List<LessonEntity>


    @Query("""
        SELECT user_isu
            FROM lessons
            WHERE pair_id = :pairId;
        """,
        nativeQuery = true)
    fun findAllUsersByPairId(pairId: Long): List<Int>
}