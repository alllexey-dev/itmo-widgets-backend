package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant

/** A cached ISU flow; each list is fresh as of its own check time. Only numbers are stored, never names. */
@Entity
@Table(name = "isu_potoks")
class IsuPotokEntity(
    @Id val potokId: Long,
    var teachersCheckedAt: Instant? = null,
    var membersCheckedAt: Instant? = null,
)

@Embeddable
data class IsuPotokTeacherId(
    @Column(nullable = false) val potokId: Long,
    @Column(nullable = false) val teacherIsu: Int,
) : java.io.Serializable

@Entity
@Table(name = "isu_potok_teachers")
class IsuPotokTeacherEntity(
    @EmbeddedId val id: IsuPotokTeacherId,
)

@Embeddable
data class IsuPotokMemberId(
    @Column(nullable = false) val potokId: Long,
    @Column(nullable = false) val isu: Int,
) : java.io.Serializable

@Entity
@Table(name = "isu_potok_members")
class IsuPotokMemberEntity(
    @EmbeddedId val id: IsuPotokMemberId,
)
