package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(name = "users")
class User(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(unique = true, nullable = false)
    val isu: Int,

    @OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true)
    val devices: MutableList<Device> = mutableListOf(),

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {

    @OneToOne(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    lateinit var settings: UserSettings
}