package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*

@Entity
class SportNotificationFilter(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    val user: User,

    @ManyToMany
    val sections: MutableList<SportSection>,

    @ManyToMany
    val buildings: MutableList<SportBuilding>,

    @ManyToMany
    val teachers: MutableList<SportTeacher>,

) {
}