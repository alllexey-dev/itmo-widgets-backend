package dev.alllexey.itmowidgets.backend.feature.sport.web

import dev.alllexey.itmowidgets.backend.feature.sport.service.UserSportLessonService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.platform.security.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(SportController::class)
@Import(SecurityConfig::class)
class SportControllerSecurityTest @Autowired constructor(private val mockMvc: MockMvc) {

    @MockitoBean
    private lateinit var userSportLessonService: UserSportLessonService

    @MockitoBean
    private lateinit var jwtAuthFilter: JwtAuthFilter

    @MockitoBean private lateinit var webSessions: WebSessionService

    @BeforeEach
    fun passRequestsThroughJwtFilterMock() {
        doAnswer { invocation ->
            invocation.getArgument<FilterChain>(2).doFilter(
                invocation.getArgument(0),
                invocation.getArgument(1),
            )
            null
        }.`when`(jwtAuthFilter).doFilter(any(), any(), any())
    }

    @Test
    fun `rejects friend sport bookings without authentication`() {
        mockMvc.perform(get("/api/sport/friends/sport-bookings"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `returns friend sport bookings to an authenticated user`() {
        val userId = UUID.randomUUID()
        `when`(userSportLessonService.getUserFriendsBookings(userId))
            .thenReturn(FriendsSportBookingsResponse(emptyList()))

        mockMvc.perform(
            get("/api/sport/friends/sport-bookings")
                .with(user(userId.toString())),
        ).andExpect(status().isOk)

        verify(userSportLessonService).getUserFriendsBookings(userId)
    }
}
