package dev.alllexey.itmowidgets.backend.feature.sport.model

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "sport_time_slots")
class SportTimeSlot(
    @Id
    val id: Long,
    timeStart: String,
    timeEnd: String,
) {
    var timeStart: String = timeStart
        protected set

    var timeEnd: String = timeEnd
        protected set

    fun refreshFrom(incoming: SportTimeSlot): Boolean {
        require(id == incoming.id)
        if (timeStart == incoming.timeStart && timeEnd == incoming.timeEnd) return false
        timeStart = incoming.timeStart
        timeEnd = incoming.timeEnd
        return true
    }
}
