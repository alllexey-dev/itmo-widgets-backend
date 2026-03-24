package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.UserSportLesson
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface UserSportLessonRepository : JpaRepository<UserSportLesson, Long> {

    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        DELETE usl
        FROM user_sport_lessons usl
        JOIN sport_lessons sl ON sl.id = usl.lesson_id
        WHERE usl.user_id = :userId
          AND sl.start > NOW()
          AND usl.lesson_id NOT IN (:lessonIds)
        """
    )
    fun deleteMissingFutureLessons(userId: UUID, lessonIds: List<Long>)

    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        INSERT INTO user_sport_lessons (user_id, lesson_id, created_at)
        SELECT :userId, sl.id, NOW()
        FROM sport_lessons sl
        WHERE sl.id IN (:lessonIds)
        ON DUPLICATE KEY UPDATE lesson_id = lesson_id
        """
    )
    fun insertLessonsIgnoreDuplicates(userId: UUID, lessonIds: List<Long>)
}