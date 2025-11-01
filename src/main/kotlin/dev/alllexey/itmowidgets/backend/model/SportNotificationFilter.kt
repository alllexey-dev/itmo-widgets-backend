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
    var sections: MutableSet<SportSection>,

    @ManyToMany
    var buildings: MutableSet<SportBuilding>,

    @ManyToMany
    var teachers: MutableSet<SportTeacher>,

) {
}