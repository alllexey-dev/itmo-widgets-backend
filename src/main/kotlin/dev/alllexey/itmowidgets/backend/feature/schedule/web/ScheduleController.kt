package dev.alllexey.itmowidgets.backend.feature.schedule.web

import dev.alllexey.itmowidgets.backend.feature.schedule.service.LessonContextService
import dev.alllexey.itmowidgets.backend.feature.schedule.service.ScheduleService
import dev.alllexey.itmowidgets.backend.feature.users.service.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.users.web.UserProfile
import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.context.annotation.Import
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * [ScheduleService] holds the privacy check and the lesson reads. The import brings it into every `@WebMvcTest`
 * slice that loads this controller, so those tests exercise the real check; component scanning still registers
 * one bean in the application.
 */
@RestController
@RequestMapping("/api/schedule")
@Import(ScheduleService::class)
class ScheduleController(
    private val userService: UserService,
    private val scheduleService: ScheduleService,
    private val lessonContextService: LessonContextService,
    private val currentGroups: CurrentStudyGroupsService,
) {

    @PostMapping("/lessons/sync")
    fun syncLessons(@RequestBody lessonSyncRequest: LessonSyncRequest, authentication: Authentication): ApiResponse<String> {
        val user = userService.findUserById(authentication.uuid())
        scheduleService.syncLessons(user, lessonSyncRequest)
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
        return ApiResponse.success(scheduleService.lessonsOf(user, isu, from, to))
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
