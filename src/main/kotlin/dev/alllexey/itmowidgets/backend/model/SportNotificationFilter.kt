package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*

@Entity
@Table(name = "sport_notification_filters")
class SportNotificationFilter(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    val user: User,

    @ManyToMany
    val sections: MutableSet<SportSection> = mutableSetOf(),

    @ManyToMany
    val buildings: MutableSet<SportBuilding> = mutableSetOf(),

    @ManyToMany
    val teachers: MutableSet<SportTeacher> = mutableSetOf(),

) {
}