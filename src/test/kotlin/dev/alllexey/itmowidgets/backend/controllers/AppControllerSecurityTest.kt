package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.configs.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.configs.SecurityConfig
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(AppController::class, properties = [
    "itmowidgets.app.version=2.3",
    "itmowidgets.app.min-version=2.1",
    "itmowidgets.app.note=Обновление <без HTML> & без Markdown",
])
@Import(SecurityConfig::class)
class AppControllerSecurityTest @Autowired constructor(private val mvc: MockMvc) {
    @MockitoBean private lateinit var jwtAuthFilter: JwtAuthFilter

    @BeforeEach
    fun passAnonymousRequestsThroughJwtFilter() {
        doAnswer { invocation ->
            invocation.getArgument<FilterChain>(2).doFilter(invocation.getArgument(0), invocation.getArgument(1))
            null
        }.`when`(jwtAuthFilter).doFilter(any(), any(), any())
    }

    @Test
    fun `anonymous legacy endpoint retains its string payload`() {
        mvc.perform(get("/api/app/version"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").isEmpty)
            .andExpect(jsonPath("$.data").isString)
            .andExpect(jsonPath("$.data").value("2.3"))
    }

    @Test
    fun `anonymous version info exposes required strings and unchanged plain text note`() {
        mvc.perform(get("/api/app/version-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.error").isEmpty)
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data.minVersion").isString)
            .andExpect(jsonPath("$.data.minVersion").value("2.1"))
            .andExpect(jsonPath("$.data.latestVersion").isString)
            .andExpect(jsonPath("$.data.latestVersion").value("2.3"))
            .andExpect(jsonPath("$.data.note").isString)
            .andExpect(jsonPath("$.data.note").value("Обновление <без HTML> & без Markdown"))
    }

    @Test
    fun `new anonymous metadata endpoint does not open protected endpoints`() {
        mvc.perform(get("/api/users/me/privacy")).andExpect(status().isForbidden)
    }
}
