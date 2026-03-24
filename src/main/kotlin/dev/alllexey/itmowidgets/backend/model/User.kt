package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(name = "users")
class User(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(unique = true)
    val isu: Int,

    @Column(columnDefinition = "TEXT")
    var pictureUrl: String?,

    @Column(columnDefinition = "TEXT")
    var name: String?,

    @Column
    val createdAt: Instant = Instant.now(),
) {

    @OneToOne(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    lateinit var settings: UserSettingsEntity
}