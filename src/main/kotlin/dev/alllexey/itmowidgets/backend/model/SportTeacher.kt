package dev.alllexey.itmowidgets.backend.model

import api.myitmo.model.IdValuePair
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "sport_teachers")
class SportTeacher(
    @Id
    val isu: Long,
    name: String,
) {
    var name: String = name
        protected set

    fun refreshFrom(incoming: SportTeacher): Boolean {
        require(isu == incoming.isu)
        if (name == incoming.name) return false
        name = incoming.name
        return true
    }

    companion object {
        fun fromApi(value: IdValuePair): SportTeacher = SportTeacher(value.id, value.value.trim())
    }
}
