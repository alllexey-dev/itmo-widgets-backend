package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapsId
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "user_settings")
class UserSettingsEntity(
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    @Id
    @Column(name = "user_id")
    var userId: UUID = user.id,

    var autoSignLimit: Int = 3,

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sport_visibility", nullable = false, length = 16, columnDefinition = "varchar(16)")
    var sportVisibility: SharingVisibility = SharingVisibility.FRIENDS,

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "schedule_visibility", nullable = false, length = 16, columnDefinition = "varchar(16)")
    var scheduleVisibility: SharingVisibility = SharingVisibility.FRIENDS,

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "friends_visibility", nullable = false, length = 16, columnDefinition = "varchar(16)")
    var friendsVisibility: SharingVisibility = SharingVisibility.ALL,
)
