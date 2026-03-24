package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "qualifications")
class QualificationEntity (
    @Id
    val code: Long,

    @Column(columnDefinition = "text")
    val name: String,
)