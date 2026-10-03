package dev.alllexey.itmowidgets.backend.feature.schedule.web

import dev.alllexey.itmowidgets.backend.feature.schedule.model.LessonEntity.Companion.toDto
import dev.alllexey.itmowidgets.backend.feature.schedule.persistence.LessonRepository
import dev.alllexey.itmowidgets.backend.feature.schedule.service.LessonContextService
import dev.alllexey.itmowidgets.backend.feature.schedule.service.LessonService
import dev.alllexey.itmowidgets.backend.feature.schedule.service.LessonService.Companion.toEntity
import dev.alllexey.itmowidgets.backend.feature.users.service.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.users.web.UserProfile
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/schedule")
class ScheduleController(
    private val lessonService: LessonService,
    private val userService: UserService,
    private val lessonRepository: LessonRepository,
    private val privacyService: UserPrivacyService,
    private val lessonContextService: LessonContextService,
    private val currentGroups: CurrentStudyGroupsService,
) {

    @PostMapping("/lessons/sync")
    fun syncLessons(@RequestBody lessonSyncRequest: LessonSyncRequest, authentication: Authentication): ApiResponse<String> {
        val user = userService.findUserById(authentication.uuid())
        val lessons = lessonSyncRequest.lessons
        val from = lessonSyncRequest.from
        val to = lessonSyncRequest.to
        val entities = lessons.map { it.toEntity(user.isu) }

        lessonService.syncLessons(user.isu, from, to, entities)

        return ApiResponse.success("Successfully synced")
    }

    @GetMapping("/lessons/user/{isu}")
    fun userLessons(
        @PathVariable isu: Int,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
        authentication: Authentication,
    ): ApiResponse<List<LessonDto>> {
        val user = userService.findUserById(authentication.uuid())
        val targetUser = userService.findUserByIsu(isu)
        if (!privacyService.canViewSchedule(user, targetUser)) {
            throw PermissionDeniedException("Schedule is not shared with this user")
        }

        val lessons = lessonRepository.findAllByIsuAndDates(isu, from, to)
        val dtos = lessons.map { it.toDto() }
        return ApiResponse.success(dtos)
    }

    /** Accepted friends on this occurrence whose schedule audience admits the viewer, with current groups. */
    @GetMapping("/lessons/{pairId}/friends")
    fun friendsOnLesson(
        @PathVariable pairId: Long,
        @RequestParam date: LocalDate,
        authentication: Authentication,
    ): ApiResponse<List<UserProfile>> {
        val user = userService.findUserById(authentication.uuid())
        return ApiResponse.success(currentGroups.profiles(lessonContextService.friendsOnLesson(user, pairId, date)))
    }
}
