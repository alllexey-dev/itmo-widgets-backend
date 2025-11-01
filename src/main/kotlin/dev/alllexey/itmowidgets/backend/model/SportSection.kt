package dev.alllexey.itmowidgets.backend.model

import api.myitmo.model.IdValuePair
import jakarta.persistence.CascadeType
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.OneToMany

@Entity
class SportSection(
    @Id
    val id: Long,

    val name: String,

    @OneToMany(mappedBy = "section", cascade = [CascadeType.ALL], orphanRemoval = true)
    val lessons: MutableList<SportLesson> = mutableListOf(),

) {

    companion object {
        fun fromApi(section: IdValuePair): SportSection {
            return SportSection(section.id.toLong(), section.value)
        }
    }

}