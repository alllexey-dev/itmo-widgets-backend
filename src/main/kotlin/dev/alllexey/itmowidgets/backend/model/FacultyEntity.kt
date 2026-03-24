package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "faculties")
class FacultyEntity(

    @Id
    val id: Long,

    @Column(columnDefinition = "text")
    var name: String,

    @Column(columnDefinition = "text")
    var shortName: String,
)