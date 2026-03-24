package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.LessonEntity
import dev.alllexey.itmowidgets.core.model.LessonData
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
                subject_id, subject,
                teacher_id, teacher_name,
                time_start, time_end,
                work_type_id, note, room, building
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                subject_id = VALUES(subject_id),
                subject = VALUES(subject),
                teacher_id = VALUES(teacher_id),
                teacher_name = VALUES(teacher_name),
                time_start = VALUES(time_start),
                time_end = VALUES(time_end),
                work_type_id = VALUES(work_type_id),
                note = VALUES(note),
                room = VALUES(room),
                building = VALUES(building)
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

            ps.setObject(5, l.subjectId, java.sql.Types.BIGINT)
            ps.setObject(6, l.subject, java.sql.Types.VARCHAR)

            ps.setObject(7, l.teacherId, java.sql.Types.BIGINT)
            ps.setObject(8, l.teacherName, java.sql.Types.VARCHAR)

            ps.setObject(9, l.timeStart)
            ps.setObject(10, l.timeEnd)

            ps.setInt(11, l.workTypeId)

            ps.setObject(12, l.note, java.sql.Types.LONGVARCHAR)
            ps.setObject(13, l.room, java.sql.Types.LONGVARCHAR)
            ps.setObject(14, l.building, java.sql.Types.LONGVARCHAR)
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
        fun LessonData.toEntity(userIsu: Int): LessonEntity {
            return LessonEntity(
                userIsu = userIsu,
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