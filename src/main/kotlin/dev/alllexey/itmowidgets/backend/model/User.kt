package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.backend.model.GroupEntity.Companion.toDto
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity.Companion.toDto
import dev.alllexey.itmowidgets.core.model.UserData
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

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_groups",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "group_id")]
    )
    var groups: MutableSet<GroupEntity> = mutableSetOf(),

    @Column
    val createdAt: Instant = Instant.now(),
) {

    @OneToOne(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    lateinit var settings: UserSettingsEntity

    companion object {
        fun User.toDto(currentUser: Boolean): UserData {
            return UserData(
                isu = isu,
                pictureUrl = pictureUrl,
                groups = groups.map { it.toDto() },
                settings = if (currentUser) settings.toDto() else null,
            )
        }
    }
}