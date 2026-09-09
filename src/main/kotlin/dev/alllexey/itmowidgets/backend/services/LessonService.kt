package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.LessonEntity
import dev.alllexey.itmowidgets.core.model.LessonDto
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class LessonService(
    private val jdbcTemplate: JdbcTemplate,
) {

    @Transactional
    fun syncLessons(isu: Int, from: LocalDate, to: LocalDate, lessons: List<LessonEntity>) {
        deleteMissing(isu, from, to, lessons.map { it.pairId })
        upsertBatch(lessons)
    }

    fun upsertBatch(lessons: List<LessonEntity>) {
        if (lessons.isEmpty()) return

        val sql = """
        INSERT INTO lessons (
            id, user_isu, date, pair_id,
            subject_id, subject_name,
            teacher_isu, teacher_fio,
            start_time, end_time,
            type, type_id,
            group_name, flow_id, flow_type_id,
            note, room, building,
            building_id, main_building_id,
            format, format_id
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (user_isu, pair_id) DO UPDATE SET
            date = EXCLUDED.date,
            subject_id = EXCLUDED.subject_id,
            subject_name = EXCLUDED.subject_name,
            teacher_isu = EXCLUDED.teacher_isu,
            teacher_fio = EXCLUDED.teacher_fio,
            start_time = EXCLUDED.start_time,
            end_time = EXCLUDED.end_time,
            type = EXCLUDED.type,
            type_id = EXCLUDED.type_id,
            group_name = EXCLUDED.group_name,
            flow_id = EXCLUDED.flow_id,
            flow_type_id = EXCLUDED.flow_type_id,
            note = EXCLUDED.note,
            room = EXCLUDED.room,
            building = EXCLUDED.building,
            building_id = EXCLUDED.building_id,
            main_building_id = EXCLUDED.main_building_id,
            format = EXCLUDED.format,
            format_id = EXCLUDED.format_id
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

        jdbcTemplate.update(sql) { ps ->
            ps.setInt(1, isu)
            ps.setObject(2, start)
            ps.setObject(3, end)
            pairIds.forEachIndexed { index, pairId -> ps.setLong(index + 4, pairId) }
        }
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
