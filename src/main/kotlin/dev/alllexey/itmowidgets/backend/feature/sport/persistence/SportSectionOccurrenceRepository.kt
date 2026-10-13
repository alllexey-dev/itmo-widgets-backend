package dev.alllexey.itmowidgets.backend.feature.sport.persistence

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionOccurrence
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

interface SportSectionOccurrenceRepository : JpaRepository<SportSectionOccurrence, Long> {

    @Query("SELECT o FROM SportSectionOccurrence o WHERE o.subscription.id = :subscriptionId AND o.start = :start")
    fun find(@Param("subscriptionId") subscriptionId: Long, @Param("start") start: OffsetDateTime): SportSectionOccurrence?

    @Query("SELECT o FROM SportSectionOccurrence o WHERE o.subscription.id = :subscriptionId AND o.end > :after")
    fun findEndingAfter(@Param("subscriptionId") subscriptionId: Long, @Param("after") after: OffsetDateTime): List<SportSectionOccurrence>
}
