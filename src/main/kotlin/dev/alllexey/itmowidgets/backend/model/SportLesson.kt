package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne

@Entity
class SportLesson(
    @Id
    val id: Long,

    @ManyToOne
    val section: SportSection,

    @ManyToOne(fetch = FetchType.EAGER)
    val timeSlot: SportTimeSlot,

    @ManyToOne
    val building: SportBuilding,

    val roomId: Long,

    val roomName: String
) {

}