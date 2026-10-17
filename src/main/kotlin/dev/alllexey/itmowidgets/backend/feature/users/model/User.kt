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

    /** «Фамилия Имя» from [familyName] and [givenName] when the id token has both, else its `name` claim. */
    @Column(columnDefinition = "TEXT")
    var name: String?,

    /** The id token's `family_name`; public in `UserData`. */
    @Column(columnDefinition = "TEXT")
    var familyName: String? = null,

    /** The id token's `given_name`; public in `UserData`. */
    @Column(columnDefinition = "TEXT")
    var givenName: String? = null,

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
