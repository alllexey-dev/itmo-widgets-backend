package dev.alllexey.itmowidgets.backend.feature.app.web

import dev.alllexey.itmowidgets.backend.feature.app.model.AppSettingEntity
import dev.alllexey.itmowidgets.backend.feature.app.persistence.AppSettingRepository
import dev.alllexey.itmowidgets.backend.feature.app.service.AppVersionSettings
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.platform.security.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
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

@WebMvcTest(
    AppController::class,
    properties = [
        "itmowidgets.app.version=2.3",
        "itmowidgets.app.min-version=2.1",
        "itmowidgets.app.note=Обновление <без HTML> & без Markdown",
        "itmowidgets.app.ios.version=2.4",
        "itmowidgets.app.ios.min-version=2.3",
        "itmowidgets.app.ios.note=",
    ],
)
@Import(SecurityConfig::class, AppVersionSettings::class)
class AppControllerSecurityTest @Autowired constructor(private val mvc: MockMvc) {
    @MockitoBean private lateinit var jwtAuthFilter: JwtAuthFilter

    @MockitoBean private lateinit var webSessions: WebSessionService

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
        `when`(settings.findAllById(AppVersionSettings.ANDROID_KEYS.all)).thenReturn(
            listOf(
                AppSettingEntity(AppVersionSettings.ANDROID_KEYS.latest, "2.4", Instant.parse("2026-09-24T09:00:00Z"), null),
                AppSettingEntity(AppVersionSettings.ANDROID_KEYS.note, "Новая версия", Instant.parse("2026-09-24T09:00:00Z"), null),
            ),
        )
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
    fun `android is the default and an explicit android platform answers the same triple`() {
        for (query in listOf("", "?platform=ANDROID", "?platform=")) {
            mvc.perform(get("/api/app/version-info$query"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data.latestVersion").value("2.3"))
                .andExpect(jsonPath("$.data.minVersion").value("2.1"))
                .andExpect(jsonPath("$.data.note").value("Обновление <без HTML> & без Markdown"))
        }
    }

    @Test
    fun `ios reads its own keys and environment and never a null note`() {
        mvc.perform(get("/api/app/version-info?platform=IOS"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data.latestVersion").value("2.4"))
            .andExpect(jsonPath("$.data.minVersion").value("2.3"))
            .andExpect(jsonPath("$.data.note").isString)
            .andExpect(jsonPath("$.data.note").value(""))

        `when`(settings.findAllById(AppVersionSettings.IOS_KEYS.all)).thenReturn(
            listOf(AppSettingEntity(AppVersionSettings.IOS_KEYS.minimum, "2.4", Instant.parse("2026-10-04T09:00:00Z"), null)),
        )
        mvc.perform(get("/api/app/version-info?platform=IOS"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.latestVersion").value("2.4"))
            .andExpect(jsonPath("$.data.minVersion").value("2.4"))
        mvc.perform(get("/api/app/version-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.minVersion").value("2.1"))
        mvc.perform(get("/api/app/version"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").value("2.3"))
    }

    /** Contract: Web detects per-platform support by this 400; Backends before 1.8.0 answer 200. */
    @Test
    fun `an unknown platform is 400 invalid_request`() {
        for (platform in listOf("WINDOWS", "ios", "Android")) {
            mvc.perform(get("/api/app/version-info?platform=$platform"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty)
                .andExpect(jsonPath("$.error.code").value("invalid_request"))
        }
    }

    @Test
    fun `new anonymous metadata endpoint does not open protected endpoints`() {
        mvc.perform(get("/api/users/me/privacy")).andExpect(status().isForbidden)
    }
}
