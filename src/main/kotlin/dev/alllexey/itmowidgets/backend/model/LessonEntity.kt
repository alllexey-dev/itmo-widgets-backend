package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.core.model.LessonData
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Entity
@Table(
    name = "lessons",
    indexes = [
        Index(name = "idx_user_date", columnList = "userIsu, date"),
        Index(name = "idx_pair_id", columnList = "pairId"),
        Index(name = "idx_user_pair", columnList = "userIsu, pairId")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uniq_user_pair", columnNames = ["userIsu", "pairId"])
    ]
)
class LessonEntity(

    val userIsu: Int,

    val date: LocalDate,

    val pairId: Long,

    @Id
    val id: UUID = UUID.nameUUIDFromBytes(("$pairId-$userIsu").toByteArray()),

    val subjectId: Long?,

    @Column(columnDefinition = "varchar(64)")
    val subject: String?,

    val teacherId: Long?,

    @Column(columnDefinition = "varchar(64)")
    val teacherName: String?,

    @Column
    val timeStart: LocalTime,
    @Column
    val timeEnd: LocalTime,

    val workTypeId: Int,

    @Column(columnDefinition = "TEXT")
    val note: String?,

    @Column(columnDefinition = "TEXT")
    val room: String?,
    @Column(columnDefinition = "TEXT")
    val building: String?

) {
    companion object {
        fun LessonEntity.toDto(): LessonData {
            return LessonData(
                date = date,
                pairId = pairId,
                subjectId = subjectId,
                subject = subject,
                teacherId = teacherId,
                teacherName = teacherName,
                timeStart = timeStart,
                timeEnd = timeEnd,
                workTypeId = workTypeId,
                note = note,
                room = room,
                building = building
            )
        }
    }
}