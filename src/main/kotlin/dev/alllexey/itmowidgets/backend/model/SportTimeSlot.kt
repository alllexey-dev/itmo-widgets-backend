package dev.alllexey.itmowidgets.backend.model

import api.myitmo.model.sport.TimeSlot
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "sport_time_slots")
class SportTimeSlot(
    @Id
    val id: Long,

    val timeStart: String,

    val timeEnd: String,
) {

    companion object {
        fun fromApi(slot: TimeSlot): SportTimeSlot {
            return SportTimeSlot(
                slot.id,
                slot.timeStart,
                slot.timeEnd
            )
        }
    }

}