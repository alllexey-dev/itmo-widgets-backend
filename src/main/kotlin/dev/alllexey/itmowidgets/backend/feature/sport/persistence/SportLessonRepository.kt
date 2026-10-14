package dev.alllexey.itmowidgets.backend.feature.sport.persistence

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportLesson
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

interface SportLessonRepository : JpaRepository<SportLesson, Long> {
    fun findAllByStartGreaterThanEqualOrderByStartAscIdAsc(start: OffsetDateTime): List<SportLesson>

    fun findAllByStart(start: OffsetDateTime): List<SportLesson>

    /** `places_limit` is written by the limits pass only and read natively; the entity does not map it. */
    @Query(nativeQuery = true, value = "SELECT places_limit FROM sport_lessons WHERE id = :id")
    fun findPlacesLimit(@Param("id") id: Long): Int?
}
