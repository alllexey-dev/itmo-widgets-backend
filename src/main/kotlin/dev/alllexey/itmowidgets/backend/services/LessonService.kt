package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.LessonEntity
import dev.alllexey.itmowidgets.core.model.LessonDto
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class LessonService(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun upsertBatch(lessons: List<LessonEntity>) {
        val sql = """
        INSERT INTO lessons (
            id, user_isu, date, pair_id,
            subject_id, subject_name,
            teacher_isu, teacher_fio,
            start, end,
            type, type_id,
            group_name, flow_id, flow_type_id,
            note, room, building,
            building_id, main_building_id,
            format, format_id
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            subject_id = VALUES(subject_id),
            subject_name = VALUES(subject_name),
            teacher_isu = VALUES(teacher_isu),
            teacher_fio = VALUES(teacher_fio),
            start = VALUES(start),
            end = VALUES(end),
            type = VALUES(type),
            type_id = VALUES(type_id),
            group_name = VALUES(group_name),
            flow_id = VALUES(flow_id),
            flow_type_id = VALUES(flow_type_id),
            note = VALUES(note),
            room = VALUES(room),
            building = VALUES(building),
            building_id = VALUES(building_id),
            main_building_id = VALUES(main_building_id),
            format = VALUES(format),
            format_id = VALUES(format_id)
    """

        jdbcTemplate.batchUpdate(
            sql,
            lessons,
            lessons.size
        ) { ps, l ->
            ps.setObject(1, l.id)
            ps.setInt(2, l.userIsu)
            ps.setObject(3, l.date)
            ps.setLong(4, l.pairId)

            ps.setLong(5, l.subjectId)
            ps.setString(6, l.subjectName)

            ps.setObject(7, l.teacherIsu, java.sql.Types.BIGINT)
            ps.setObject(8, l.teacherFio, java.sql.Types.VARCHAR)

            ps.setObject(9, l.start)
            ps.setObject(10, l.end)

            ps.setString(11, l.type)
            ps.setInt(12, l.typeId)

            ps.setString(13, l.groupName)
            ps.setLong(14, l.flowId)
            ps.setInt(15, l.flowTypeId)

            ps.setObject(16, l.note, java.sql.Types.LONGVARCHAR)
            ps.setObject(17, l.room, java.sql.Types.LONGVARCHAR)
            ps.setObject(18, l.building, java.sql.Types.LONGVARCHAR)

            ps.setObject(19, l.buildingId, java.sql.Types.INTEGER)
            ps.setObject(20, l.mainBuildingId, java.sql.Types.INTEGER)

            ps.setString(21, l.format)
            ps.setInt(22, l.formatId)
        }
    }

    fun deleteMissing(
        isu: Int,
        start: LocalDate,
        end: LocalDate,
        pairIds: List<Long>
    ) {
        if (pairIds.isEmpty()) {
            jdbcTemplate.update(
                """
            DELETE FROM lessons
            WHERE user_isu = ?
              AND date BETWEEN ? AND ?
            """,
                isu, start, end
            )
            return
        }

        val inSql = pairIds.joinToString(",") { "?" }

        val sql = """
        DELETE FROM lessons
        WHERE user_isu = ?
          AND date BETWEEN ? AND ?
          AND pair_id NOT IN ($inSql)
        """

        jdbcTemplate.update(
            sql,
            *(listOf(isu, start, end) + pairIds).toTypedArray()
        )
    }

    companion object {
        fun LessonDto.toEntity(userIsu: Int): LessonEntity {
            return LessonEntity(
                userIsu = userIsu,
                date = date,
                pairId = pairId,

                subjectId = subjectId,
                subjectName = subjectName,

                teacherIsu = teacherIsu,
                teacherFio = teacherFio,

                start = start,
                end = end,

                type = type,
                typeId = typeId,

                groupName = groupName,
                flowId = flowId,
                flowTypeId = flowTypeId,

                note = note,
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