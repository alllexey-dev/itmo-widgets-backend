package dev.alllexey.itmowidgets.backend.feature.sport.web

import dev.alllexey.itmowidgets.backend.feature.sport.service.SportSectionService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.platform.security.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(SportSectionController::class)
@Import(SecurityConfig::class)
class SportSectionControllerSecurityTest @Autowired constructor(private val mockMvc: MockMvc) {

    @MockitoBean private lateinit var sections: SportSectionService

    @MockitoBean private lateinit var jwtAuthFilter: JwtAuthFilter

    @MockitoBean private lateinit var webSessions: WebSessionService

    @BeforeEach
    fun passRequestsThroughJwtFilterMock() {
        doAnswer { invocation ->
            invocation.getArgument<FilterChain>(2).doFilter(invocation.getArgument(0), invocation.getArgument(1))
            null
        }.`when`(jwtAuthFilter).doFilter(any(), any(), any())
    }

    @Test
    fun `rejects every section route without authentication`() {
        mockMvc.perform(get("/api/sport/sections/my")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/sport/sections/slots?lessonIds=1")).andExpect(status().isUnauthorized)
        mockMvc.perform(post("/api/sport/sections/create").contentType(MediaType.APPLICATION_JSON).content("""{"lessonId":1}"""))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(post("/api/sport/sections/1/stop")).andExpect(status().isUnauthorized)
        verifyNoInteractions(sections)
    }

    @Test
    fun `serves the caller's own sections and slots by the authenticated id`() {
        val userId = UUID.randomUUID()
        `when`(sections.getMine(userId)).thenReturn(emptyList())
        `when`(sections.getSlots(userId, listOf(1L, 2L))).thenReturn(emptyList())

        mockMvc.perform(get("/api/sport/sections/my").with(user(userId.toString()))).andExpect(status().isOk)
        mockMvc.perform(get("/api/sport/sections/slots?lessonIds=1,2").with(user(userId.toString()))).andExpect(status().isOk)
        mockMvc.perform(post("/api/sport/sections/5/stop").with(user(userId.toString()))).andExpect(status().isOk)

        verify(sections).getMine(userId)
        verify(sections).getSlots(userId, listOf(1L, 2L))
        verify(sections).stop(userId, 5L)
    }
}
