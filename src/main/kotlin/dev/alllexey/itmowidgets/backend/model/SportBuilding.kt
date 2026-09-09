package dev.alllexey.itmowidgets.backend.model

import api.myitmo.model.IdValuePair
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "sport_buildings")
class SportBuilding(
    @Id
    val id: Long,
    name: String,
) {
    var name: String = name
        protected set

    fun refreshFrom(incoming: SportBuilding): Boolean {
        require(id == incoming.id)
        if (name == incoming.name) return false
        name = incoming.name
        return true
    }

    companion object {
        fun fromApi(value: IdValuePair): SportBuilding = SportBuilding(value.id, value.value.trim())
    }
}
