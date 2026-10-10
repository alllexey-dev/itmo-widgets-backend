package dev.alllexey.itmowidgets.backend.feature.users.web

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.feature.users.service.AccountDeletionService
import dev.alllexey.itmowidgets.backend.feature.users.service.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserProfileService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.ActiveWebSession
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebLoginService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.platform.error.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** `DELETE /api/users/me` through the real bearer and web session filters: only a sign-in within ten minutes deletes. */
@WebMvcTest(UserController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class, AccountDeletionControllerSecurityTest.TimeConfig::class)
class AccountDeletionControllerSecurityTest @Autowired constructor(private val mvc: MockMvc) {
    @MockitoBean private lateinit var verifier: ItmoJwtVerifier

    @MockitoBean private lateinit var users: UserService

    @MockitoBean private lateinit var webSessions: WebSessionService

    @MockitoBean private lateinit var webLogins: WebLoginService

    @MockitoBean private lateinit var accountDeletion: AccountDeletionService

    @MockitoBean private lateinit var access: AdminAccess

    @MockitoBean private lateinit var privacy: UserPrivacyService

    @MockitoBean private lateinit var currentGroups: CurrentStudyGroupsService

    @MockitoBean private lateinit var profiles: UserProfileService

    @MockitoBean private lateinit var restrictions: RestrictionService

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    @BeforeEach
    fun fixture() {
        for (token in listOf(
            FRESH,
            AT_LIMIT,
            STALE,
            WITHOUT_AUTH_TIME,
        )) {
            `when`(verifier.verifyAccessToken(token)).thenReturn(JWT.decode(token))
        }
        `when`(users.resolveIdByIsu(ISU)).thenReturn(USER_ID)
        `when`(users.isuOf(USER_ID)).thenReturn(ISU)
    }

    @Test
    fun `a bearer signed in within ten minutes deletes the account`() {
        for (token in listOf(FRESH, AT_LIMIT)) {
            mvc.perform(delete("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
                .andExpect(status().isOk).andExpect(jsonPath("$.success").value(true))
        }
        verify(accountDeletion, times(2)).delete(ISU)
    }

    @Test
    fun `a bearer with an older or no auth_time is refused`() {
        for (token in listOf(STALE, WITHOUT_AUTH_TIME)) {
            mvc.perform(delete("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
                .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("recent_sign_in_required"))
        }
        verify(accountDeletion, never()).delete(anyInt())
    }

    @Test
    fun `a web session created within ten minutes deletes the account with the web header`() {
        session(NOW.minus(RECENT_AUTH))

        mvc.perform(delete("/api/users/me").cookie(COOKIE).header(WEB_REQUEST, "1")).andExpect(status().isOk)

        verify(accountDeletion).delete(ISU)
    }

    @Test
    fun `an older web session is refused`() {
        session(NOW.minus(RECENT_AUTH).minusSeconds(1))

        mvc.perform(delete("/api/users/me").cookie(COOKIE).header(WEB_REQUEST, "1"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("recent_sign_in_required"))

        verify(accountDeletion, never()).delete(anyInt())
    }

    @Test
    fun `a fresh web session without the web header is refused as csrf`() {
        session(NOW)

        mvc.perform(delete("/api/users/me").cookie(COOKIE))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("csrf"))

        verify(accountDeletion, never()).delete(anyInt())
    }

    @Test
    fun `an anonymous caller gets 401`() {
        mvc.perform(delete("/api/users/me"))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.error.code").value("unauthorized"))

        verify(accountDeletion, never()).delete(anyInt())
    }

    private fun session(signedInAt: Instant) {
        `when`(webSessions.resolve(SESSION)).thenReturn(ActiveWebSession(USER_ID, signedInAt = signedInAt, freshForAdmin = true))
    }

    private companion object {
        const val ISU = 970001
        const val SESSION = "synthetic-session-token"
        const val WEB_REQUEST = "X-Web-Request"
        val USER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000970001")
        val NOW: Instant = Instant.parse("2026-10-10T12:00:00Z")
        val RECENT_AUTH: Duration = Duration.ofMinutes(10)
        val COOKIE = Cookie("iw_session", SESSION)
        val FRESH = token(NOW.minusSeconds(30))
        val AT_LIMIT = token(NOW.minus(RECENT_AUTH))
        val STALE = token(NOW.minus(RECENT_AUTH).minusSeconds(1))
        val WITHOUT_AUTH_TIME: String = JWT.create().withClaim("isu", ISU).sign(Algorithm.none())

        fun token(authTime: Instant): String =
            JWT.create().withClaim("isu", ISU).withClaim("auth_time", authTime.epochSecond).sign(Algorithm.none())
    }
}
