package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.UserSportLesson
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@Repository
interface UserSportLessonRepository : JpaRepository<UserSportLesson, Long> {

    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        DELETE FROM user_sport_lessons usl
        USING sport_lessons sl
        WHERE sl.id = usl.lesson_id
          AND usl.user_id = :userId
          AND sl.starts_at > :now
          AND usl.lesson_id NOT IN (:lessonIds)
        """
    )
    fun deleteMissingFutureLessons(userId: UUID, lessonIds: List<Long>, now: Instant)

    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        INSERT INTO user_sport_lessons (user_id, lesson_id, created_at)
        SELECT :userId, sl.id, :now
        FROM sport_lessons sl
        WHERE sl.id IN (:lessonIds)
        ON CONFLICT (user_id, lesson_id) DO NOTHING
        """
    )
    fun insertLessonsIgnoreDuplicates(userId: UUID, lessonIds: List<Long>, now: Instant)

    fun existsByUserIdAndLessonId(userId: UUID, lessonId: Long): Boolean

    @Query(
        """
            SELECT usl from UserSportLesson usl
            WHERE usl.lesson.end >= :cutoff
            AND usl.user.isu in :userIsus
        """
    )
    fun findByUserIsuIn(userIsus: List<Int>, cutoff: OffsetDateTime): List<UserSportLesson>
}
