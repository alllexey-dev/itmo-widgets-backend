package dev.alllexey.itmowidgets.backend.model

import api.myitmo.model.IdValuePair
import jakarta.persistence.Entity
import jakarta.persistence.Id

@Entity
class SportTeacher(
    @Id
    val isu: Long,

    val name: String,
) {

    companion object {
        fun fromApi(teacher: IdValuePair): SportTeacher {
            return SportTeacher(teacher.id.toLong(), teacher.value)
        }
    }

}