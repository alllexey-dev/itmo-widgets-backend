package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "sport_filters")
class SportFilter(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    val user: User,

    @ManyToMany
    var sections: MutableSet<SportSection> = mutableSetOf(),

    @ManyToMany
    var buildings: MutableSet<SportBuilding> = mutableSetOf(),

    @ManyToMany
    var teachers: MutableSet<SportTeacher> = mutableSetOf(),

    @ManyToMany
    var timeSlots: MutableSet<SportTimeSlot> = mutableSetOf(),

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    ) {
}