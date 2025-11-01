package dev.alllexey.itmowidgets.backend.model

import api.myitmo.model.TimeSlot
import jakarta.persistence.Entity
import jakarta.persistence.Id

@Entity
class SportTimeSlot(
    @Id
    val id: Long,

    val timeStart: String,

    val timeEnd: String,
) {

    companion object {
        fun fromApi(slot: TimeSlot): SportTimeSlot {
            return SportTimeSlot(
                slot.id.toLong(),
                slot.timeStart,
                slot.timeEnd
            )
        }
    }

}