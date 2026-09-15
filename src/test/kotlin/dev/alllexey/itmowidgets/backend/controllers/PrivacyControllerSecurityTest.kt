package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.configs.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.configs.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.configs.SecurityConfig
import dev.alllexey.itmowidgets.backend.dto.UserPrivacySettings
import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.repositories.LessonRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.backend.repositories.UserSportLessonRepository
import dev.alllexey.itmowidgets.backend.services.*
import jakarta.servlet.FilterChain
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.stream.Stream
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(UserController::class, ScheduleController::class, SportController::class, FriendController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class, UserPrivacyService::class,
    UserSportLessonService::class, UserProfileService::class,
    PrivacyControllerSecurityTest.TimeConfig::class)
class PrivacyControllerSecurityTest @Autowired constructor(private val mvc: MockMvc) {
    @MockitoBean private lateinit var notifications: FriendshipNotificationService
    @MockitoBean(name = "friendshipNotificationExecutor") private lateinit var notificationExecutor: org.springframework.core.task.TaskExecutor
    @MockitoBean private lateinit var jwtAuthFilter: JwtAuthFilter
    @MockitoBean private lateinit var users: UserService
    @MockitoBean private lateinit var friends: FriendService
    @MockitoBean private lateinit var userRepo: UserRepository
    @MockitoBean private lateinit var lessons: LessonRepository
    @MockitoBean private lateinit var lessonService: LessonService
    @MockitoBean private lateinit var sportLessons: UserSportLessonRepository
    @MockitoBean private lateinit var freeSign: SportFreeSignService
    @MockitoBean private lateinit var autoSign: SportAutoSignService

    private val viewer = person(100001, SharingVisibility.NOBODY)

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-09-08T09:00:00Z"), ZoneOffset.UTC)
    }

    @BeforeEach
    fun authenticationFixture() {
        doAnswer { invocation ->
            invocation.getArgument<FilterChain>(2).doFilter(invocation.getArgument(0), invocation.getArgument(1))
            null
        }.`when`(jwtAuthFilter).doFilter(any(), any(), any())
        `when`(users.findUserById(viewer.id)).thenReturn(viewer)
    }

    @ParameterizedTest
    @MethodSource("accessCases")
    fun `schedule and confirmed sport enforce owner audience without reciprocity`(visibility: SharingVisibility, relation: Relation) {
        val owner = if (relation == Relation.SELF) viewer.apply {
            settings.scheduleVisibility = visibility
            settings.sportVisibility = visibility
        } else person(200002, visibility)
        `when`(users.findUserByIsu(owner.isu)).thenReturn(owner)
        `when`(friends.areFriends(viewer.isu, owner.isu)).thenReturn(relation == Relation.FRIEND)
        `when`(lessons.findAllByIsuAndDates(owner.isu, FROM, TO)).thenReturn(emptyList())
        `when`(sportLessons.findByUserIsuIn(listOf(owner.isu), NOW)).thenReturn(emptyList())
        val allowed = relation == Relation.SELF || visibility == SharingVisibility.ALL ||
            (visibility == SharingVisibility.FRIENDS && relation == Relation.FRIEND)
        val expected = if (allowed) 200 else 403
        mvc.perform(get("/api/schedule/lessons/user/${owner.isu}").param("from", FROM.toString())
            .param("to", TO.toString()).with(user(viewer.id.toString())))
            .andExpect(status().`is`(expected))
        mvc.perform(get("/api/sport/users/${owner.isu}/bookings").with(user(viewer.id.toString())))
            .andExpect(status().`is`(expected))
        if (!allowed) {
            verify(lessons, never()).findAllByIsuAndDates(owner.isu, FROM, TO)
            verify(sportLessons, never()).findByUserIsuIn(listOf(owner.isu), NOW)
        }
        if (allowed) {
            verify(freeSign).getUserEntries(owner.id)
            verify(autoSign).getUserEntries(owner.id)
        } else {
            verifyNoInteractions(freeSign, autoSign)
        }
    }

    @Test
    fun `participants omit private owners and use viewer scoped boolean capabilities`() {
        val publicOwner = person(200002, SharingVisibility.ALL).apply { settings.sportVisibility = SharingVisibility.NOBODY }
        val visibleFriend = person(300003, SharingVisibility.FRIENDS)
        val stranger = person(400004, SharingVisibility.FRIENDS)
        val hidden = person(500005, SharingVisibility.NOBODY)
        val candidates = listOf(publicOwner, visibleFriend, stranger, hidden)
        val ids = candidates.map { it.isu }
        `when`(lessons.findAllUsersByPairId(50)).thenReturn(ids + viewer.isu)
        `when`(userRepo.findAllByIsuIn(ids)).thenReturn(candidates)
        `when`(friends.areFriends(viewer.isu, visibleFriend.isu)).thenReturn(true)
        mvc.perform(get("/api/schedule/lessons/50/users").with(user(viewer.id.toString())))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].isu").value(publicOwner.isu))
            .andExpect(jsonPath("$.data[0].capabilities.canViewSchedule").value(true))
            .andExpect(jsonPath("$.data[0].capabilities.canViewSport").value(false))
            .andExpect(jsonPath("$.data[0].settings").doesNotExist())
            .andExpect(jsonPath("$.data[0].scheduleVisibility").doesNotExist())
            .andExpect(jsonPath("$.data[0].sportVisibility").doesNotExist())
            .andExpect(jsonPath("$.data[0].capabilities.scheduleVisibility").doesNotExist())
            .andExpect(jsonPath("$.data[1].isu").value(visibleFriend.isu))
    }

    @Test
    fun `friend request cards cannot expose target raw friends visibility`() {
        val stranger = person(200002, SharingVisibility.FRIENDS)
        `when`(friends.getIncomingRequests(viewer.isu)).thenReturn(listOf(stranger.isu))
        `when`(userRepo.findAllByIsuIn(listOf(stranger.isu))).thenReturn(listOf(stranger))
        `when`(friends.relationship(viewer.isu, stranger.isu))
            .thenReturn(dev.alllexey.itmowidgets.backend.dto.RelationshipState.INCOMING)
        mvc.perform(get("/api/friends/requests/incoming").with(user(viewer.id.toString())))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].user.capabilities.canViewSchedule").value(false))
            .andExpect(jsonPath("$.data[0].user.capabilities.canViewSport").value(false))
            .andExpect(jsonPath("$.data[0].user.settings").doesNotExist())
            .andExpect(jsonPath("$.data[0].user.scheduleVisibility").doesNotExist())
            .andExpect(jsonPath("$.data[0].user.sportVisibility").doesNotExist())
            .andExpect(jsonPath("$.data[0].user.capabilities.sportVisibility").doesNotExist())
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/users/me/privacy", "/api/sport/users/200002/bookings",
        "/api/schedule/lessons/user/200002?from=2026-09-08&to=2026-09-09", "/api/schedule/lessons/50/users"])
    fun `all privacy protected reads require authentication`(path: String) {
        mvc.perform(get(path)).andExpect(status().isForbidden)
        verifyNoInteractions(users, userRepo, lessons, sportLessons)
    }

    @Test
    fun `privacy writes require authentication`() {
        mvc.perform(put("/api/users/me/privacy").contentType(MediaType.APPLICATION_JSON)
            .content("""{"scheduleVisibility":"ALL","sportVisibility":"ALL"}"""))
            .andExpect(status().isForbidden)
        verifyNoInteractions(users)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "{}", "null", "{\"scheduleVisibility\":\"ALL\"}",
        "{\"sportVisibility\":\"ALL\"}", "{\"scheduleVisibility\":null,\"sportVisibility\":\"ALL\"}",
        "{\"scheduleVisibility\":\"UNKNOWN\",\"sportVisibility\":\"ALL\"}",
        "{\"scheduleVisibility\":\"all\",\"sportVisibility\":\"ALL\"}",
        "{\"scheduleVisibility\":0,\"sportVisibility\":\"NOBODY\"}",
        "{\"scheduleVisibility\":1,\"sportVisibility\":\"NOBODY\"}",
        "{\"scheduleVisibility\":0.0,\"sportVisibility\":\"NOBODY\"}",
        "{\"scheduleVisibility\":true,\"sportVisibility\":\"NOBODY\"}",
        "{\"scheduleVisibility\":[],\"sportVisibility\":\"NOBODY\"}",
        "{\"scheduleVisibility\":{},\"sportVisibility\":\"NOBODY\"}",
        "{\"scheduleVisibility\":\"NOBODY\",\"sportVisibility\":0}"])
    fun `missing null and unknown values fail closed without changing settings`(body: String) {
        mvc.perform(put("/api/users/me/privacy").with(user(viewer.id.toString()))
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
        verifyNoInteractions(users)
    }

    @ParameterizedTest
    @MethodSource("privacyPairs")
    fun `own privacy round trip has two independent enums`(schedule: SharingVisibility, sport: SharingVisibility) {
        val privacy = UserPrivacySettings(schedule, sport)
        `when`(users.updatePrivacySettings(viewer, privacy)).thenReturn(privacy)
        `when`(users.privacySettings(viewer)).thenReturn(privacy)
        mvc.perform(put("/api/users/me/privacy").with(user(viewer.id.toString())).contentType(MediaType.APPLICATION_JSON)
            .content("""{"scheduleVisibility":"$schedule","sportVisibility":"$sport"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.scheduleVisibility").value(schedule.name))
            .andExpect(jsonPath("$.data.sportVisibility").value(sport.name))
        mvc.perform(get("/api/users/me/privacy").with(user(viewer.id.toString())))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.scheduleVisibility").value(schedule.name))
            .andExpect(jsonPath("$.data.sportVisibility").value(sport.name))
        verify(users).updatePrivacySettings(viewer, privacy)
    }

    @Test
    fun `own user data reports self capabilities not nobody privacy preferences`() {
        mvc.perform(get("/api/users/me/data").with(user(viewer.id.toString())))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.capabilities.canViewSchedule").value(true))
            .andExpect(jsonPath("$.data.capabilities.canViewSport").value(true))
            .andExpect(jsonPath("$.data.settings").doesNotExist())
            .andExpect(jsonPath("$.data.scheduleVisibility").doesNotExist())
            .andExpect(jsonPath("$.data.sportVisibility").doesNotExist())
            .andExpect(jsonPath("$.data.capabilities.scheduleVisibility").doesNotExist())
    }

    @Test
    fun `removed legacy settings endpoint is not available`() {
        mvc.perform(get("/api/users/me/settings").with(user(viewer.id.toString())))
            .andExpect(status().isNotFound)
        mvc.perform(put("/api/users/me/settings").with(user(viewer.id.toString())).contentType(MediaType.APPLICATION_JSON)
            .content("""{"scheduleSharing":true,"sportSharing":false}"""))
            .andExpect(status().isNotFound)
        verifyNoInteractions(users)
    }

    @Test
    fun `boolean legacy payload is not accepted by the audience endpoint`() {
        mvc.perform(put("/api/users/me/privacy").with(user(viewer.id.toString()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"scheduleSharing":true,"sportSharing":true}"""))
            .andExpect(status().isBadRequest)
        verifyNoInteractions(users)
    }

    enum class Relation { SELF, FRIEND, STRANGER }

    companion object {
        private val FROM = LocalDate.of(2026, 9, 8)
        private val TO = FROM.plusDays(1)
        private val NOW = OffsetDateTime.parse("2026-09-08T09:00:00Z")
        private fun person(isu: Int, visibility: SharingVisibility) = User(isu = isu, name = "Synthetic user", pictureUrl = null).apply {
            settings = UserSettingsEntity(user = this, scheduleVisibility = visibility, sportVisibility = visibility)
        }
        @JvmStatic fun accessCases(): Stream<Arguments> = SharingVisibility.entries.flatMap { visibility ->
            Relation.entries.map { relation -> Arguments.of(visibility, relation) }
        }.stream()
        @JvmStatic fun privacyPairs(): Stream<Arguments> = SharingVisibility.entries.flatMap { schedule ->
            SharingVisibility.entries.map { sport -> Arguments.of(schedule, sport) }
        }.stream()
    }
}
