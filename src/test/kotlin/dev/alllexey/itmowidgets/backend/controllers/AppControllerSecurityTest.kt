package dev.alllexey.itmowidgets.backend.controllers

import dev.alllexey.itmowidgets.backend.configs.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.configs.SecurityConfig
import dev.alllexey.itmowidgets.backend.model.AppSettingEntity
import dev.alllexey.itmowidgets.backend.repositories.AppSettingRepository
import dev.alllexey.itmowidgets.backend.services.AppVersionSettings
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(AppController::class, properties = [
    "itmowidgets.app.version=2.3",
    "itmowidgets.app.min-version=2.1",
    "itmowidgets.app.note=Обновление <без HTML> & без Markdown",
])
@Import(SecurityConfig::class, AppVersionSettings::class)
class AppControllerSecurityTest @Autowired constructor(private val mvc: MockMvc) {
    @MockitoBean private lateinit var jwtAuthFilter: JwtAuthFilter
    @MockitoBean private lateinit var webSessions: dev.alllexey.itmowidgets.backend.services.WebSessionService
    @MockitoBean private lateinit var settings: AppSettingRepository

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
    fun `stored admin settings replace the environment values key by key with the same contract`() {
        `when`(settings.findAllById(AppVersionSettings.KEYS)).thenReturn(listOf(
            AppSettingEntity(AppVersionSettings.LATEST, "2.4", Instant.parse("2026-09-24T09:00:00Z"), null),
            AppSettingEntity(AppVersionSettings.NOTE, "Новая версия", Instant.parse("2026-09-24T09:00:00Z"), null),
        ))
        mvc.perform(get("/api/app/version"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").value("2.4"))
        mvc.perform(get("/api/app/version-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data.latestVersion").value("2.4"))
            .andExpect(jsonPath("$.data.minVersion").value("2.1"))
            .andExpect(jsonPath("$.data.note").value("Новая версия"))
    }

    @Test
    fun `new anonymous metadata endpoint does not open protected endpoints`() {
        mvc.perform(get("/api/users/me/privacy")).andExpect(status().isForbidden)
    }
}
