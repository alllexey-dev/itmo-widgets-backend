package dev.alllexey.itmowidgets.backend.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.alllexey.itmowidgets.backend.configs.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.configs.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.configs.SecurityConfig
import dev.alllexey.itmowidgets.backend.model.LessonEntity
import dev.alllexey.itmowidgets.backend.model.LessonEntity.Companion.toDto
import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.repositories.LessonRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.backend.services.FriendService
import dev.alllexey.itmowidgets.backend.dto.RelationshipState
import dev.alllexey.itmowidgets.backend.dto.UserProfile
import dev.alllexey.itmowidgets.backend.services.LessonContextService
import dev.alllexey.itmowidgets.backend.services.LessonService
import dev.alllexey.itmowidgets.backend.services.LessonService.Companion.toEntity
import dev.alllexey.itmowidgets.backend.services.UserPrivacyService
import dev.alllexey.itmowidgets.backend.services.UserService
import dev.alllexey.itmowidgets.core.model.LessonDto
import dev.alllexey.itmowidgets.core.model.LessonSyncRequest
import jakarta.servlet.FilterChain
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(ScheduleController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class, UserPrivacyService::class, UnavailableStudyGroupsConfig::class,
    ScheduleControllerSecurityTest.TimeConfig::class)
class ScheduleControllerSecurityTest @Autowired constructor(
    private val mvc: MockMvc,
    private val objectMapper: ObjectMapper,
) {
    @MockitoBean private lateinit var jwtAuthFilter: JwtAuthFilter
    @MockitoBean private lateinit var webSessions: dev.alllexey.itmowidgets.backend.services.WebSessionService
    @MockitoBean private lateinit var users: UserService
    @MockitoBean private lateinit var friends: FriendService
    @MockitoBean private lateinit var userRepository: UserRepository
    @MockitoBean private lateinit var lessons: LessonRepository
    @MockitoBean private lateinit var lessonService: LessonService
    @MockitoBean private lateinit var lessonContextService: LessonContextService

    private val viewer = person(100001, SharingVisibility.NOBODY)

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC)
    }

    @BeforeEach
    fun authenticationFixture() {
        doAnswer { invocation ->
            invocation.getArgument<FilterChain>(2).doFilter(
                invocation.getArgument(0), invocation.getArgument(1),
            )
            null
        }.`when`(jwtAuthFilter).doFilter(any(), any(), any())
        `when`(users.findUserById(viewer.id)).thenReturn(viewer)
    }

    @Test
    fun `anonymous schedule sync cannot reach owner lookup or persistence`() {
        val request = LessonSyncRequest(listOf(lesson(50, FROM)), FROM, TO)

        mvc.perform(post("/api/schedule/lessons/sync")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isForbidden)

        verifyNoInteractions(users, friends, userRepository, lessons, lessonService)
    }

    @Test
    fun `sync binds every lesson to authenticated owner despite extra query and JSON identities`() {
        val dtos = listOf(lesson(50, FROM), lesson(51, TO).copy(
            teacherIsu = null, teacherFio = null, note = null,
            room = null, building = null, buildingId = null, mainBuildingId = null,
        ))
        val body = objectMapper.valueToTree<ObjectNode>(LessonSyncRequest(dtos, FROM, TO)).apply {
            put("isu", OTHER_ISU)
            put("userIsu", OTHER_ISU)
            put("userId", OTHER_ID.toString())
            get("lessons").forEach { node ->
                (node as ObjectNode).put("userIsu", OTHER_ISU)
                    .put("isu", OTHER_ISU).put("userId", OTHER_ID.toString())
            }
        }
        val synchronizedLessons = mutableListOf<List<LessonEntity>>()
        doAnswer { invocation ->
            synchronizedLessons.add(invocation.getArgument(3))
            null
        }.`when`(lessonService).syncLessons(
            eq(viewer.isu), eq(FROM) ?: FROM, eq(TO) ?: TO, anyList(),
        )

        mvc.perform(post("/api/schedule/lessons/sync")
            .with(user(viewer.id.toString()))
            .param("isu", OTHER_ISU.toString())
            .param("userIsu", OTHER_ISU.toString())
            .param("userId", OTHER_ID.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(body)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))

        val actual = synchronizedLessons.single()
        assertEquals(listOf(viewer.isu, viewer.isu), actual.map { it.userIsu })
        assertEquals(dtos, actual.map { it.toDto() })
        verify(users).findUserById(viewer.id)
        verify(lessonService).syncLessons(viewer.isu, FROM, TO, actual)
        verifyNoMoreInteractions(users, lessonService)
        verifyNoInteractions(friends, userRepository, lessons)
    }

    @Test
    fun `authenticated owner can submit an empty snapshot for the exact requested range`() {
        val request = LessonSyncRequest(emptyList(), FROM, TO)

        mvc.perform(post("/api/schedule/lessons/sync")
            .with(user(viewer.id.toString()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))

        verify(users).findUserById(viewer.id)
        verify(lessonService).syncLessons(viewer.isu, FROM, TO, emptyList())
        verifyNoMoreInteractions(users, lessonService)
        verifyNoInteractions(friends, userRepository, lessons)
    }

    @ParameterizedTest
    @MethodSource("accessCases")
    fun `schedule reads use owner audience and never require reciprocal sharing`(
        visibility: SharingVisibility,
        relation: Relation,
    ) {
        val owner = if (relation == Relation.SELF) viewer.apply {
            settings.scheduleVisibility = visibility
        } else person(OTHER_ISU, visibility)
        `when`(users.findUserByIsu(owner.isu)).thenReturn(owner)
        `when`(friends.areFriends(viewer.isu, owner.isu)).thenReturn(relation == Relation.FRIEND)
        val dtos = listOf(lesson(50, FROM), lesson(51, TO))
        `when`(lessons.findAllByIsuAndDates(owner.isu, FROM, TO))
            .thenReturn(dtos.map { it.toEntity(owner.isu) })
        val allowed = relation == Relation.SELF || visibility == SharingVisibility.ALL ||
            (visibility == SharingVisibility.FRIENDS && relation == Relation.FRIEND)

        val result = mvc.perform(get("/api/schedule/lessons/user/${owner.isu}")
            .param("from", FROM.toString()).param("to", TO.toString())
            .with(user(viewer.id.toString())))
            .andExpect(status().`is`(if (allowed) 200 else 403))

        if (allowed) {
            result.andExpect(jsonPath("$.success").value(true))
            val data = objectMapper.readTree(result.andReturn().response.contentAsByteArray).get("data")
            assertEquals(objectMapper.readTree(objectMapper.writeValueAsBytes(dtos)), data)
            verify(lessons).findAllByIsuAndDates(owner.isu, FROM, TO)
            verifyNoMoreInteractions(lessons)
        } else {
            result.andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("permission_denied"))
            verifyNoInteractions(lessons)
        }
        if (relation != Relation.SELF) {
            assertEquals(SharingVisibility.NOBODY, viewer.settings.scheduleVisibility)
            assertEquals(SharingVisibility.NOBODY, viewer.settings.sportVisibility)
        }
        verifyNoInteractions(userRepository, lessonService)
    }

    @Test
    fun `friends on a lesson come from the context service for the viewer and omit private fields`() {
        val friend = person(300003, SharingVisibility.FRIENDS)
        `when`(friends.areFriends(viewer.isu, friend.isu)).thenReturn(true)
        // Built before stubbing: userDataFor consults the friends mock itself.
        val profile = UserProfile(UserPrivacyService(friends).userDataFor(viewer, friend), RelationshipState.FRIENDS)
        `when`(lessonContextService.friendsOnLesson(viewer, 50, FROM)).thenReturn(listOf(profile))

        val response = mvc.perform(get("/api/schedule/lessons/50/friends")
            .param("date", FROM.toString())
            .with(user(viewer.id.toString())))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].relationship").value("FRIENDS"))
            .andReturn().response

        val entry = objectMapper.readTree(response.contentAsByteArray).get("data").get(0).get("user")
        assertEquals(friend.isu, entry.get("isu").asInt())
        assertEquals("Synthetic user ${friend.isu}", entry.get("name").asText())
        assertTrue(entry.get("capabilities").get("canViewSchedule").asBoolean())
        for (privateField in listOf("settings", "scheduleVisibility", "sportVisibility", "userId", "id")) {
            assertFalse(entry.has(privateField), "Friend must omit $privateField")
        }
        verify(lessonContextService).friendsOnLesson(viewer, 50, FROM)
        verifyNoInteractions(lessons, userRepository, lessonService)
    }

    @Test
    fun `friends on a lesson require the occurrence date`() {
        mvc.perform(get("/api/schedule/lessons/50/friends").with(user(viewer.id.toString())))
            .andExpect(status().isBadRequest)

        verifyNoInteractions(lessonContextService, lessons)
    }

    @Test
    fun `the unrestricted participant list no longer exists`() {
        mvc.perform(get("/api/schedule/lessons/50/users").with(user(viewer.id.toString())))
            .andExpect(status().isNotFound)

        verifyNoInteractions(lessonContextService, lessons, userRepository)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "/api/schedule/lessons/user/200002?from=2026-09-08&to=2026-09-09",
        "/api/schedule/lessons/50/friends?date=2026-09-08",
    ])
    fun `anonymous schedule and friend reads cannot reach repositories`(path: String) {
        mvc.perform(get(path)).andExpect(status().isForbidden)

        verifyNoInteractions(users, friends, userRepository, lessons, lessonService, lessonContextService)
    }

    enum class Relation { SELF, FRIEND, STRANGER }

    companion object {
        private val FROM = LocalDate.of(2026, 9, 8)
        private val TO = FROM.plusDays(1)
        private const val OTHER_ISU = 200002
        private val OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000200002")

        private fun person(isu: Int, visibility: SharingVisibility) = User(
            id = UUID.nameUUIDFromBytes("schedule-security-$isu".toByteArray()),
            isu = isu,
            name = "Synthetic user $isu",
            pictureUrl = null,
            createdAt = Instant.parse("2026-09-08T09:00:00Z"),
        ).apply {
            settings = UserSettingsEntity(user = this, scheduleVisibility = visibility, sportVisibility = visibility)
        }

        private fun lesson(pairId: Long, date: LocalDate) = LessonDto(
            pairId = pairId,
            date = date,
            start = LocalTime.of(9, 0),
            end = LocalTime.of(10, 30),
            type = "Лекция",
            typeId = 1,
            note = "Synthetic note $pairId",
            subjectName = "Synthetic subject $pairId",
            subjectId = 100 + pairId,
            groupName = "M3100",
            flowId = 200 + pairId,
            flowTypeId = 2,
            teacherIsu = 300001,
            teacherFio = "Synthetic teacher",
            room = "101",
            building = "Synthetic building",
            buildingId = 1,
            mainBuildingId = 13,
            format = "Очный",
            formatId = 1,
        )

        @JvmStatic
        fun accessCases(): Stream<Arguments> = SharingVisibility.entries.flatMap { visibility ->
            Relation.entries.map { relation -> Arguments.of(visibility, relation) }
        }.stream()
    }
}
