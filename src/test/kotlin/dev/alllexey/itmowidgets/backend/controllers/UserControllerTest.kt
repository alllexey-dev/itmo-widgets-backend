package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.configs.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.configs.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.configs.SecurityConfig
import dev.alllexey.itmowidgets.backend.dto.RelationshipState
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.model.FriendshipEntity
import dev.alllexey.itmowidgets.backend.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.repositories.FriendshipRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.backend.services.*
import jakarta.servlet.FilterChain
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.AfterEach
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.core.task.TaskExecutor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(UserController::class, FriendController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class, UserProfileService::class,
    FriendService::class, UserPrivacyService::class, UserControllerTest.TimeConfig::class)
class UserControllerTest @Autowired constructor(private val mvc: MockMvc) {
    @MockitoBean private lateinit var notifications: FriendshipNotificationService
    @MockitoBean(name = "friendshipNotificationExecutor") private lateinit var notificationExecutor: TaskExecutor
    @MockitoBean private lateinit var jwtAuthFilter: JwtAuthFilter
    @MockitoBean private lateinit var users: UserService
    @MockitoBean private lateinit var userRepository: UserRepository
    @MockitoBean private lateinit var friendships: FriendshipRepository
    private val viewer = person(100001)
    private val owner = person(100002)
    private var row: FriendshipEntity? = null

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC)
    }

    @BeforeEach
    fun fixture() {
        // This MVC slice has no transaction manager; real commit/rollback lives in PostgreSQL tests.
        TransactionSynchronizationManager.initSynchronization()
        doAnswer { it.getArgument<FilterChain>(2).doFilter(it.getArgument(0), it.getArgument(1)); null }
            .`when`(jwtAuthFilter).doFilter(any(), any(), any())
        `when`(users.findUserById(viewer.id)).thenReturn(viewer)
        `when`(users.findUserByIsu(viewer.isu)).thenReturn(viewer)
        `when`(users.findUserByIsu(owner.isu)).thenReturn(owner)
        for (person in listOf(viewer, owner)) `when`(userRepository.lockByIsu(person.isu)).thenReturn(person.id)
        `when`(friendships.findBetween(viewer.isu, owner.isu)).thenAnswer { row }
        `when`(friendships.save(any(FriendshipEntity::class.java))).thenAnswer {
            it.getArgument<FriendshipEntity>(0).also { row = it }
        }
        doAnswer { row = null; null }.`when`(friendships).delete(any(FriendshipEntity::class.java))
    }

    @AfterEach
    fun clearSynchronization() { TransactionSynchronizationManager.clearSynchronization() }

    @ParameterizedTest
    @EnumSource(value = RelationshipState::class, names = ["NONE", "OUTGOING", "INCOMING", "FRIENDS"])
    fun `profile capabilities reflect each direction not the viewers own audience`(state: RelationshipState) {
        row = relation(state)
        viewer.settings.scheduleVisibility = SharingVisibility.NOBODY
        viewer.settings.sportVisibility = SharingVisibility.NOBODY
        for (schedule in SharingVisibility.entries) {
            for (sport in SharingVisibility.entries) {
                owner.settings.scheduleVisibility = schedule
                owner.settings.sportVisibility = sport
                mvc.perform(get("/api/users/${owner.isu}").with(user(viewer.id.toString())))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.data.relationship").value(state.name))
                    .andExpect(jsonPath("$.data.user.isu").value(owner.isu))
                    .andExpect(jsonPath("$.data.user.capabilities.canViewSchedule").value(visible(schedule, state)))
                    .andExpect(jsonPath("$.data.user.capabilities.canViewSport").value(visible(sport, state)))
                    .andExpect(jsonPath("$.data.user.settings").doesNotExist())
                    .andExpect(jsonPath("$.data.user.id").doesNotExist())
                    .andExpect(jsonPath("$.data.user.scheduleVisibility").doesNotExist())
                    .andExpect(jsonPath("$.data.user.sportVisibility").doesNotExist())
            }
        }
    }

    @Test
    fun `self profile retains self permissions but never calls self a friend`() {
        viewer.settings.scheduleVisibility = SharingVisibility.NOBODY
        viewer.settings.sportVisibility = SharingVisibility.NOBODY
        mvc.perform(get("/api/users/${viewer.isu}").with(user(viewer.id.toString())))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.relationship").value("NONE"))
            .andExpect(jsonPath("$.data.user.capabilities.canViewSchedule").value(true))
            .andExpect(jsonPath("$.data.user.capabilities.canViewSport").value(true))
    }

    @Test
    fun `lookup omits unknown ISUs deduplicates preserves order and computes capabilities`() {
        row = relation(RelationshipState.INCOMING)
        `when`(userRepository.findAllByIsuIn(listOf(999999, owner.isu, viewer.isu)))
            .thenReturn(listOf(viewer, owner))
        lookup("""{"isus":[999999,100002,100001,100002]}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.data.users.length()").value(2))
            .andExpect(jsonPath("$.data.users[0].user.isu").value(owner.isu))
            .andExpect(jsonPath("$.data.users[0].relationship").value("INCOMING"))
            .andExpect(jsonPath("$.data.users[0].user.capabilities.canViewSchedule").value(false))
            .andExpect(jsonPath("$.data.users[0].user.settings").doesNotExist())
            .andExpect(jsonPath("$.data.users[1].user.isu").value(viewer.isu))
            .andExpect(jsonPath("$.data.users[1].user.capabilities.canViewSport").value(true))
        verify(users, never()).findOrCreateByIsu(org.mockito.ArgumentMatchers.anyInt())
    }

    @Test
    fun `empty lookup succeeds without loading targets and exactly 50 ISUs are allowed`() {
        lookup("""{"isus":[]}""").andExpect(status().isOk).andExpect(jsonPath("$.data.users").isEmpty)
        verifyNoInteractions(userRepository, friendships)
        val isus = (100001..100050).toList()
        `when`(userRepository.findAllByIsuIn(isus)).thenReturn(emptyList())
        lookup("""{"isus":$isus}""").andExpect(status().isOk)
        verify(userRepository).findAllByIsuIn(isus)
    }

    @Test
    fun `malformed lookup fails before querying any user`() {
        val invalid = listOf("{}", "null", "[]", """{"isus":null}""", """{"isus":[null]}""",
            """{"isus":[0]}""", """{"isus":[-1]}""", """{"isus":[1.2]}""", """{"isus":["100002"]}""",
            """{"isus":[true]}""", """{"isus":[2147483648]}""", """{"isus":{}}""")
        for (body in invalid) lookup(body).andExpect(status().isBadRequest)
        verifyNoInteractions(userRepository, users, friendships)
    }

    @Test
    fun `lookup size is checked before deduplication`() {
        lookup("""{"isus":[${List(51) { "100002" }.joinToString()}]}""").andExpect(status().isBadRequest)
        verifyNoInteractions(userRepository, users, friendships)
    }

    @Test
    fun `unknown and invalid target profiles do not create users`() {
        `when`(users.findUserByIsu(999999)).thenThrow(NotFoundException("User not found"))
        mvc.perform(get("/api/users/999999").with(user(viewer.id.toString()))).andExpect(status().isNotFound)
        mvc.perform(get("/api/users/0").with(user(viewer.id.toString()))).andExpect(status().isBadRequest)
        verify(users, never()).findOrCreateByIsu(org.mockito.ArgumentMatchers.anyInt())
    }

    @Test
    fun `request cancel accept reject and remove return the resulting full profile`() {
        action("request").andExpect(status().isOk).andExpect(jsonPath("$.data.relationship").value("OUTGOING"))
        action("accept").andExpect(status().isConflict)
        action("reject").andExpect(status().isConflict)
        action("cancel").andExpect(status().isOk).andExpect(jsonPath("$.data.relationship").value("NONE"))
        row = relation(RelationshipState.INCOMING)
        action("reject").andExpect(status().isOk).andExpect(jsonPath("$.data.relationship").value("NONE"))
        row = relation(RelationshipState.INCOMING)
        action("accept").andExpect(status().isOk).andExpect(jsonPath("$.data.relationship").value("FRIENDS"))
            .andExpect(jsonPath("$.data.user.capabilities.canViewSchedule").value(true))
        mvc.perform(delete("/api/friends/${owner.isu}").with(user(viewer.id.toString())))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.relationship").value("NONE"))
            .andExpect(jsonPath("$.data.user.capabilities.canViewSchedule").value(false))
    }

    @Test
    fun `friendship lists use the authenticated viewer and profile wrappers`() {
        for ((path, state) in listOf("" to RelationshipState.FRIENDS,
            "/requests/incoming" to RelationshipState.INCOMING, "/requests/outgoing" to RelationshipState.OUTGOING)) {
            row = relation(state)
            `when`(friendships.findUserFriendsIsu(viewer.isu)).thenReturn(listOf(owner.isu))
            `when`(friendships.findIncomingRequests(viewer.isu)).thenReturn(listOf(owner.isu))
            `when`(friendships.findOutgoingRequests(viewer.isu)).thenReturn(listOf(owner.isu))
            `when`(userRepository.findAllByIsuIn(listOf(owner.isu))).thenReturn(listOf(owner))
            mvc.perform(get("/api/friends$path").with(user(viewer.id.toString())))
                .andExpect(status().isOk).andExpect(jsonPath("$.data[0].relationship").value(state.name))
                .andExpect(jsonPath("$.data[0].user.capabilities.canViewSport").value(state == RelationshipState.FRIENDS))
                .andExpect(jsonPath("$.data[0].user.settings").doesNotExist())
        }
    }

    @Test
    fun `every new route denies unauthenticated access before services`() {
        for (path in listOf("/api/users/100002/friends", "/api/users/100002", "/api/friends", "/api/friends/requests/incoming", "/api/friends/requests/outgoing")) {
            mvc.perform(get(path)).andExpect(status().isForbidden)
        }
        for (action in listOf("request", "accept", "reject", "cancel")) {
            mvc.perform(post("/api/friends/100002/$action")).andExpect(status().isForbidden)
        }
        mvc.perform(delete("/api/friends/100002")).andExpect(status().isForbidden)
        mvc.perform(post("/api/users/lookup").contentType(MediaType.APPLICATION_JSON).content("""{"isus":[100002]}"""))
            .andExpect(status().isForbidden)
        verifyNoInteractions(users, userRepository, friendships)
    }

    @Test
    fun `removed legacy friend routes cannot mutate friendships`() {
        for (path in listOf("add", "remove")) {
            mvc.perform(post("/api/friends/$path").with(user(viewer.id.toString()))
                .contentType(MediaType.APPLICATION_JSON).content("""{"isu":100002}"""))
                .andExpect(status().is4xxClientError)
        }
        mvc.perform(get("/api/friends/get").with(user(viewer.id.toString()))).andExpect(status().is4xxClientError)
        verifyNoInteractions(users, userRepository, friendships)
    }

    @ParameterizedTest
    @EnumSource(value = RelationshipState::class, names = ["NONE", "OUTGOING", "INCOMING", "FRIENDS"])
    fun `friends audience is enforced before reading the list and profiles use viewer permissions`(state: RelationshipState) {
        row = relation(state)
        val third = person(100003)
        // Owner knows third; viewer does not. No owner capability or relationship may leak through.
        `when`(friendships.findUserFriendsIsu(owner.isu)).thenReturn(listOf(third.isu))
        `when`(userRepository.findAllByIsuIn(listOf(third.isu))).thenReturn(listOf(third))
        for (audience in SharingVisibility.entries) {
            owner.settings.friendsVisibility = audience
            viewer.settings.friendsVisibility = SharingVisibility.NOBODY
            clearInvocations(friendships, userRepository)
            val allowed = visible(audience, state)
            mvc.perform(get("/api/users/${owner.isu}").with(user(viewer.id.toString())))
                .andExpect(jsonPath("$.data.user.capabilities.canViewFriends").value(allowed))
                .andExpect(jsonPath("$.data.user.friendsVisibility").doesNotExist())
            val response = mvc.perform(get("/api/users/${owner.isu}/friends").with(user(viewer.id.toString())))
                .andExpect(status().`is`(if (allowed) 200 else 403))
            if (allowed) {
                response.andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].relationship").value("NONE"))
                    .andExpect(jsonPath("$.data[0].user.capabilities.canViewSchedule").value(false))
                    .andExpect(jsonPath("$.data[0].user.capabilities.canViewSport").value(false))
                    .andExpect(jsonPath("$.data[0].user.settings").doesNotExist())
            } else {
                verify(friendships, never()).findUserFriendsIsu(owner.isu)
                verifyNoInteractions(userRepository)
            }
        }
    }

    @Test
    fun `self empty invalid and missing friends lists have distinct results`() {
        viewer.settings.friendsVisibility = SharingVisibility.NOBODY
        mvc.perform(get("/api/users/${viewer.isu}/friends").with(user(viewer.id.toString())))
            .andExpect(status().isOk).andExpect(jsonPath("$.data").isEmpty)
        mvc.perform(get("/api/users/0/friends").with(user(viewer.id.toString())))
            .andExpect(status().isBadRequest)
        `when`(users.findUserByIsu(999999)).thenThrow(NotFoundException("User not found"))
        mvc.perform(get("/api/users/999999/friends").with(user(viewer.id.toString())))
            .andExpect(status().isNotFound)
        verify(users, never()).findOrCreateByIsu(org.mockito.ArgumentMatchers.anyInt())
    }

    private fun lookup(body: String) = mvc.perform(post("/api/users/lookup").with(user(viewer.id.toString()))
        .contentType(MediaType.APPLICATION_JSON).content(body))
    private fun action(action: String) = mvc.perform(post("/api/friends/${owner.isu}/$action").with(user(viewer.id.toString())))
    private fun visible(visibility: SharingVisibility, state: RelationshipState) =
        visibility == SharingVisibility.ALL || (visibility == SharingVisibility.FRIENDS && state == RelationshipState.FRIENDS)
    private fun relation(state: RelationshipState): FriendshipEntity? = when (state) {
        RelationshipState.NONE -> null
        else -> FriendshipEntity(
            requester = if (state == RelationshipState.INCOMING) owner else viewer,
            addressee = if (state == RelationshipState.INCOMING) viewer else owner,
            createdAt = Instant.parse("2026-09-15T09:00:00Z"),
        ).apply {
            if (state == RelationshipState.FRIENDS) {
                status = FriendshipEntity.Status.ACCEPTED
                respondedAt = createdAt.plusSeconds(30)
            }
        }
    }
    private fun person(isu: Int) = User(isu = isu, name = "Synthetic user", pictureUrl = null).apply {
        settings = UserSettingsEntity(user = this)
    }
}
