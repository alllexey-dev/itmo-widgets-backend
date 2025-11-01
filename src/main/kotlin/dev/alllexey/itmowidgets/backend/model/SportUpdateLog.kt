package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.CascadeType
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import java.time.Instant

@Entity
class SportUpdateLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    val updateTimestamp: Instant = Instant.now(),

    val newLessonsAdded: Int,

    @OneToMany(
        mappedBy = "sportUpdateLog",
        cascade = [CascadeType.ALL],
        fetch = FetchType.LAZY
    )
    var newLessons: MutableList<SportLesson> = mutableListOf()
)