package dev.alllexey.itmowidgets.backend.feature.sport.model

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
}
