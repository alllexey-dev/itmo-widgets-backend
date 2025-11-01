package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
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

    @ManyToOne
    val teacher: SportTeacher,

    val roomId: Long,

    val roomName: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sport_update_log_id")
    var sportUpdateLog: SportUpdateLog? = null
) {

}