package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.repositories.UserSportLessonRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserSportLessonService(
    private val repo: UserSportLessonRepository,
    private val userService: UserService,
    private val sportFreeSignService: SportFreeSignService,
    private val sportAutoSignService: SportAutoSignService
) {

    @Transactional
    fun syncLessons(userId: UUID, lessonIds: List<Long>) {
        val user = userService.findUserById(userId)
        sportFreeSignService.sync(user, lessonIds)
        sportAutoSignService.sync(user, lessonIds)
        if (user.settings.sportLogging) {
            repo.deleteMissingFutureLessons(userId, lessonIds.ifEmpty { listOf(-1L) })
            repo.insertLessonsIgnoreDuplicates(userId, lessonIds)
        }
    }
}