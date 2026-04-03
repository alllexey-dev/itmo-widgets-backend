package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.model.LessonEntity.Companion.toDto
import dev.alllexey.itmowidgets.backend.model.User.Companion.toDto
import dev.alllexey.itmowidgets.backend.repositories.LessonRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.backend.services.LessonService
import dev.alllexey.itmowidgets.backend.services.LessonService.Companion.toEntity
import dev.alllexey.itmowidgets.backend.services.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.backend.services.UserService
import dev.alllexey.itmowidgets.core.model.ApiResponse
import dev.alllexey.itmowidgets.core.model.LessonDto
import dev.alllexey.itmowidgets.core.model.LessonSyncRequest
import dev.alllexey.itmowidgets.core.model.UserData
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
    private val userRepository: UserRepository
) {

    @PostMapping("/lessons/sync")
    fun syncLessons(
        @RequestBody lessonSyncRequest: LessonSyncRequest,
        authentication: Authentication
    ): ApiResponse<String> {
        val user = userService.findUserById(authentication.uuid())
        val lessons = lessonSyncRequest.lessons
        val from = lessonSyncRequest.from
        val to = lessonSyncRequest.to
        val entities = lessons.map { it.toEntity(user.isu) }
        val pairIds = lessons.map { it.pairId }

        lessonService.deleteMissing(user.isu, from, to, pairIds)
        lessonService.upsertBatch(entities)

        return ApiResponse.success("Successfully synced")
    }

    @GetMapping("/lessons/user/{isu}")
    fun userLessons(
        @PathVariable isu: Int,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
        authentication: Authentication
    ): ApiResponse<List<LessonDto>> {
        // todo: verify friendship
        val user = userService.findUserById(authentication.uuid())
        val targetUser = userService.findUserByIsu(isu)

        val lessons = lessonRepository.findAllByIsuAndDates(isu, from, to)
        val dtos = lessons.map { it.toDto() }
        return ApiResponse.success(dtos)
    }

    @GetMapping("/lessons/{pairId}/users")
    fun usersByPairId(
        @PathVariable pairId: Long,
        authentication: Authentication
    ): ApiResponse<List<UserData>> {
        // todo: verify friendship
        val user = userService.findUserById(authentication.uuid())
        val userIsu = lessonRepository.findAllUsersByPairId(pairId).filterNot { it == user.isu }
        val users = userRepository.findAllByIsuIn(userIsu)

        return ApiResponse.success(users.map { it.toDto() })
    }
}