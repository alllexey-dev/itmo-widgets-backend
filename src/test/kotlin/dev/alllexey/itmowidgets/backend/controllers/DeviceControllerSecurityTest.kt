package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.configs.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.configs.SecurityConfig
import dev.alllexey.itmowidgets.backend.services.DeviceService
import jakarta.servlet.FilterChain
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(DeviceController::class)
@Import(SecurityConfig::class)
class DeviceControllerSecurityTest @Autowired constructor(
    private val mockMvc: MockMvc
) {

    @MockitoBean
    private lateinit var deviceService: DeviceService

    @MockitoBean
    private lateinit var jwtAuthFilter: JwtAuthFilter
    @MockitoBean private lateinit var webSessions: dev.alllexey.itmowidgets.backend.services.WebSessionService

    @BeforeEach
    fun passRequestsThroughJwtFilterMock() {
        doAnswer { invocation ->
            invocation.getArgument<FilterChain>(2).doFilter(
                invocation.getArgument(0),
                invocation.getArgument(1)
            )
            null
        }.`when`(jwtAuthFilter).doFilter(
            any(),
            any(),
            any()
        )
    }

    @Test
    fun `rejects unregister without authentication`() {
        mockMvc.perform(
            delete("/api/device/current")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fcmToken":"current-token"}""")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `allows authenticated user to unregister own device token`() {
        val userId = UUID.randomUUID()

        mockMvc.perform(
            delete("/api/device/current")
                .with(user(userId.toString()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fcmToken":"current-token"}""")
        ).andExpect(status().isOk)

        verify(deviceService).unregisterDevice(userId, "current-token")
    }
}
