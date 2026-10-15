package dev.alllexey.itmowidgets.backend.feature.users.model

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

    /** From the ITMO.ID id token, for the owner only: never sent to other users. */
    @Column(columnDefinition = "TEXT")
    var email: String? = null,

    /** `male` or `female` as ITMO.ID sends it; public in `UserData` for declension. */
    @Column(columnDefinition = "TEXT")
    var gender: String? = null,

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_groups",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "group_id")],
    )
    var groups: MutableSet<GroupEntity> = mutableSetOf(),

    @Column
    val createdAt: Instant,
) {

    @OneToOne(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    lateinit var settings: UserSettingsEntity
}
