package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.core.model.LessonDto
import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.*

@Entity
@Table(
    name = "lessons",
    indexes = [
        Index(name = "idx_user_date", columnList = "user_isu, date"),
        Index(name = "idx_pair_id", columnList = "pair_id"),
        Index(name = "idx_user_pair", columnList = "user_isu, pair_id")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uniq_user_pair", columnNames = ["user_isu", "pair_id"])
    ]
)
class LessonEntity(

    @Column(name = "user_isu")
    val userIsu: Int,

    val date: LocalDate,

    @Column(name = "pair_id")
    val pairId: Long,

    @Id
    val id: UUID = UUID.nameUUIDFromBytes(("$pairId-$userIsu").toByteArray()),

    // subject
    val subjectId: Long,
    @Column(columnDefinition = "TEXT")
    val subjectName: String,

    // teacher
    val teacherIsu: Long?,
    @Column(columnDefinition = "TEXT")
    val teacherFio: String?,

    // time
    val start: LocalTime,
    val end: LocalTime,

    // type
    @Column(columnDefinition = "TEXT")
    val type: String,
    val typeId: Int,

    // flow
    @Column(columnDefinition = "TEXT")
    val groupName: String,
    val flowId: Long,
    val flowTypeId: Int,

    // additional
    @Column(columnDefinition = "TEXT")
    val note: String?,

    @Column(columnDefinition = "TEXT")
    val room: String?,

    @Column(columnDefinition = "TEXT")
    val building: String?,

    val buildingId: Int?,
    val mainBuildingId: Int?,

    @Column(columnDefinition = "TEXT")
    val format: String,
    val formatId: Int
) {
    companion object {
        fun LessonEntity.toDto(): LessonDto {
            return LessonDto(
                pairId = pairId,
                date = date,
                start = start,
                end = end,
                type = type,
                typeId = typeId,
                note = note,
                subjectName = subjectName,
                subjectId = subjectId,
                groupName = groupName,
                flowId = flowId,
                flowTypeId = flowTypeId,
                teacherIsu = teacherIsu,
                teacherFio = teacherFio,
                room = room,
                building = building,
                buildingId = buildingId,
                mainBuildingId = mainBuildingId,
                format = format,
                formatId = formatId
            )
        }
    }
}