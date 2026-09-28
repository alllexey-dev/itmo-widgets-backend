package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.LessonEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
interface LessonRepository : JpaRepository<LessonEntity, UUID> {

    @Query("""
        SELECT *
            FROM lessons
            WHERE user_isu = :isu
              AND date BETWEEN :start AND :end
            ORDER BY date, start_time
        """,
        nativeQuery = true)
    fun findAllByIsuAndDates(isu: Int, start: LocalDate, end: LocalDate): List<LessonEntity>


    @Query("""
        SELECT DISTINCT user_isu
            FROM lessons
            WHERE pair_id = :pairId
              AND date = :date
        """,
        nativeQuery = true)
    fun findAllUsersByPairIdAndDate(pairId: Long, date: LocalDate): List<Int>

    @Query(value = "SELECT EXISTS (SELECT 1 FROM lessons WHERE teacher_isu = :teacherIsu)", nativeQuery = true)
    fun existsTeacher(teacherIsu: Long): Boolean

    /** Flows of the user's uploaded lessons with this teacher, the most recently taught first. */
    @Query("""
        SELECT flow_id
            FROM lessons
            WHERE user_isu = :userIsu
              AND teacher_isu = :teacherIsu
            GROUP BY flow_id
            ORDER BY MAX(date) DESC, flow_id
        """,
        nativeQuery = true)
    fun findTeacherFlows(userIsu: Int, teacherIsu: Long): List<Long>
}
