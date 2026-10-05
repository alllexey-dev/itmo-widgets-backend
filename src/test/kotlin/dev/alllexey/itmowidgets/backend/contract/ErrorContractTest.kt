package dev.alllexey.itmowidgets.backend.contract

import com.auth0.jwt.exceptions.JWTVerificationException
import dev.alllexey.itmowidgets.backend.feature.app.persistence.AppSettingRepository
import dev.alllexey.itmowidgets.backend.feature.app.service.AppVersionSettings
import dev.alllexey.itmowidgets.backend.feature.app.web.AppController
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.platform.error.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

/**
 * Error bodies that do not depend on a route, produced by the real security chain: the request is denied before
 * any controller runs.
 */
@WebMvcTest(AppController::class)
@Import(SecurityConfig::class, AppVersionSettings::class, GlobalExceptionHandler::class)
class ErrorContractTest @Autowired constructor(private val mvc: MockMvc) {
    @MockitoBean private lateinit var verifier: ItmoJwtVerifier

    @MockitoBean private lateinit var users: UserService

    @MockitoBean private lateinit var webSessions: WebSessionService

    @MockitoBean private lateinit var settings: AppSettingRepository

    @Test
    fun `missing or invalid credentials answer the unauthorized body`() {
        `when`(verifier.verifyAccessToken(INVALID)).thenThrow(JWTVerificationException("synthetic invalid token"))
        `when`(webSessions.resolve(EXPIRED_SESSION)).thenReturn(null)
        val requests = listOf(
            get("/api/users/me/data"),
            post("/api/friends/100002/request"),
            get("/api/users/me/data").header(HttpHeaders.AUTHORIZATION, "Bearer $INVALID"),
            get("/api/admin/dashboard").cookie(Cookie("iw_session", EXPIRED_SESSION)),
        )

        val bodies = requests.map(::unauthorized)

        ContractFiles.check(ContractCatalog.errors.single { it.id == "unauthorized" }.file, bodies.first())
        bodies.drop(1).forEach { assertEquals(emptyList(), ContractJson.differences(bodies.first(), it)) }
    }

    private fun unauthorized(request: MockHttpServletRequestBuilder) = ContractJson.parse(
        mvc.perform(request).andExpect(status().isUnauthorized).andReturn().response.contentAsString,
    )

    private companion object {
        const val INVALID = "synthetic-invalid-token"
        const val EXPIRED_SESSION = "synthetic-expired-session"
    }
}
