package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "sport_lessons")
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
    var sportUpdateLog: SportUpdateLog? = null
) {

}