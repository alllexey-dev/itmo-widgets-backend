package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.Column
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "sport_update_logs")
class SportUpdateLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val updateTimestamp: Instant,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val outcome: SportUpdateOutcome,

    @Column(nullable = false)
    val durationMillis: Long,

    @Column(nullable = false)
    val receivedLessons: Int,

    @Column(nullable = false)
    val newLessonsAdded: Int,

    @Column(nullable = false)
    val updatedLessons: Int,

    @Column(nullable = false)
    val skippedLessons: Int,

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    val errorCategory: SportUpdateErrorCategory? = null,

    @OneToMany(
        fetch = FetchType.LAZY
    )
    @JoinTable(
        name = "sport_update_logs_new_lessons",
        joinColumns = [JoinColumn(name = "sport_update_log_id")],
        inverseJoinColumns = [JoinColumn(name = "new_lessons_id")],
        uniqueConstraints = [UniqueConstraint(name = "uq_sport_update_log_lesson", columnNames = ["new_lessons_id"])]
    )
    var newLessons: MutableList<SportLesson> = mutableListOf()
)
