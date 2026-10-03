package dev.alllexey.itmowidgets.backend.feature.users.model

import dev.alllexey.itmowidgets.backend.feature.users.web.GroupData
import jakarta.persistence.*
import java.util.*

@Entity
@Table(name = "groups")
class GroupEntity(

    @Id
    val id: UUID,

    val name: String,

    val course: Int,

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "qualification_id")
    val qualification: QualificationEntity,

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "faculty_id")
    val faculty: FacultyEntity,

    @ManyToMany(mappedBy = "groups")
    val users: MutableSet<User> = mutableSetOf(),
) {
    companion object {
        fun GroupEntity.toDto(): GroupData = GroupData(
            name = name,
            course = course,
            facultyShortName = faculty.shortName,
        )
    }
}
