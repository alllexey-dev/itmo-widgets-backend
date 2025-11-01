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

    val name: String,
) {

    companion object {
        fun fromApi(building: IdValuePair): SportBuilding {
            return SportBuilding(building.id.toLong(), building.value)
        }
    }

}