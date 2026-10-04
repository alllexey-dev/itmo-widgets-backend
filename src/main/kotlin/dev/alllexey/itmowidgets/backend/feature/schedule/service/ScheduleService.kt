package dev.alllexey.itmowidgets.backend.feature.schedule.service

import dev.alllexey.itmowidgets.backend.feature.schedule.model.LessonEntity.Companion.toDto
import dev.alllexey.itmowidgets.backend.feature.schedule.persistence.LessonRepository
import dev.alllexey.itmowidgets.backend.feature.schedule.service.LessonService.Companion.toEntity
import dev.alllexey.itmowidgets.backend.feature.schedule.web.LessonDto
import dev.alllexey.itmowidgets.backend.feature.schedule.web.LessonSyncRequest
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import org.springframework.stereotype.Service
import java.time.LocalDate

/** The schedule routes behind `ScheduleController`: the owner's upload and the audience-checked read. */
@Service
class ScheduleService(
    private val lessonService: LessonService,
    private val userService: UserService,
    private val privacyService: UserPrivacyService,
    private val lessonRepository: LessonRepository,
) {

    /** Every uploaded lesson belongs to [owner], whatever identity the request body names. */
    fun syncLessons(owner: User, request: LessonSyncRequest) {
        val lessons = request.lessons.map { it.toEntity(owner.isu) }
        lessonService.syncLessons(owner.isu, request.from, request.to, lessons)
    }

    /** The owner's audience decides; a denied viewer gets 403 `permission_denied` before any lesson is read. */
    fun lessonsOf(viewer: User, ownerIsu: Int, from: LocalDate, to: LocalDate): List<LessonDto> {
        val owner = userService.findUserByIsu(ownerIsu)
        if (!privacyService.canViewSchedule(viewer, owner)) {
            throw PermissionDeniedException("Schedule is not shared with this user")
        }
        return lessonRepository.findAllByIsuAndDates(ownerIsu, from, to).map { it.toDto() }
    }
}
