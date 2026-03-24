package dev.alllexey.itmowidgets.backend.model

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
)