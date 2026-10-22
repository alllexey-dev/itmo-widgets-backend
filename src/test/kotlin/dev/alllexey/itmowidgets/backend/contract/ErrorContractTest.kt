package dev.alllexey.itmowidgets.backend.contract

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import dev.alllexey.itmowidgets.backend.feature.app.persistence.AppSettingRepository
import dev.alllexey.itmowidgets.backend.feature.app.service.AppVersionSettings
import dev.alllexey.itmowidgets.backend.feature.app.web.AppController
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.platform.error.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.platform.security.ClientSessions
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import dev.alllexey.itmowidgets.backend.testing.TestConnections
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Error bodies that do not depend on a route, produced by the real security chain: the request is denied before
 * any controller runs.
 */
@WebMvcTest(AppController::class)
@Import(SecurityConfig::class, AppVersionSettings::class, GlobalExceptionHandler::class, ErrorContractTest.Config::class)
class ErrorContractTest @Autowired constructor(private val mvc: MockMvc) {
    @MockitoBean private lateinit var verifier: ItmoJwtVerifier

    @MockitoBean private lateinit var users: UserService

    @MockitoBean private lateinit var settings: AppSettingRepository

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean fun connectionResolver() = TestConnections.resolver(
            mapOf(STALE_SESSION to TestConnections.web(UUID.randomUUID(), Instant.EPOCH, freshForAdmin = false)),
        )

        @Bean fun clientSessions() = ClientSessions { it == CLIENT_SID }
    }

    @Test
    fun `missing or invalid credentials answer the unauthorized body`() {
        `when`(verifier.verifyAccessToken(INVALID)).thenThrow(JWTVerificationException("synthetic invalid token"))
        val requests = listOf(
            get("/api/users/me/data"),
            post("/api/friends/100002/request"),
            get("/api/users/me/data").header(HttpHeaders.AUTHORIZATION, "Bearer $INVALID"),
            get("/api/admin/dashboard").cookie(Cookie("iw_client", EXPIRED_SESSION)),
        )

        val bodies = requests.map(::unauthorized)

        ContractFiles.check(ContractCatalog.errors.single { it.id == "unauthorized" }.file, bodies.first())
        bodies.drop(1).forEach { assertEquals(emptyList(), ContractJson.differences(bodies.first(), it)) }
    }

    @Test
    fun `a web connection approved too long ago for admin routes answers the reauth body`() {
        val response = mvc.perform(get("/api/admin/dashboard").cookie(Cookie("iw_client", STALE_SESSION)))
            .andExpect(status().isUnauthorized).andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer")).andReturn().response

        ContractFiles.check(ContractCatalog.errors.single { it.id == "reauth_required" }.file, ContractJson.parse(response.contentAsString))
    }

    @Test
    fun `a user who has not enrolled answers the not_enrolled body`() {
        `when`(verifier.verifyAccessToken(UNENROLLED)).thenReturn(JWT.decode(UNENROLLED))

        val response = mvc.perform(get("/api/users/me/data").header(HttpHeaders.AUTHORIZATION, "Bearer $UNENROLLED"))
            .andExpect(status().isForbidden).andReturn().response

        ContractFiles.check(ContractCatalog.errors.single { it.id == "not_enrolled" }.file, ContractJson.parse(response.contentAsString))
    }

    @Test
    fun `a connected client's own ITMO token answers the client_session body`() {
        `when`(verifier.verifyAccessToken(CLIENT)).thenReturn(JWT.decode(CLIENT))

        val response = mvc.perform(get("/api/users/me/data").header(HttpHeaders.AUTHORIZATION, "Bearer $CLIENT"))
            .andExpect(status().isForbidden).andReturn().response

        ContractFiles.check(ContractCatalog.errors.single { it.id == "client_session" }.file, ContractJson.parse(response.contentAsString))
    }

    private fun unauthorized(request: MockHttpServletRequestBuilder) = ContractJson.parse(
        mvc.perform(request).andExpect(status().isUnauthorized).andReturn().response.contentAsString,
    )

    private companion object {
        const val INVALID = "synthetic-invalid-token"
        const val EXPIRED_SESSION = "iwc_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val STALE_SESSION = "iwc_sssssssssssssssssssssssssssssssssssssssssss"
        const val CLIENT_SID = "8d1f0c2e-1b6a-4c55-9a43-0f2d7e6b1a01"
        val UNENROLLED: String = JWT.create().withClaim("isu", 100001).sign(Algorithm.none())
        val CLIENT: String = JWT.create().withClaim("isu", 100001).withClaim("sid", CLIENT_SID).sign(Algorithm.none())
    }
}
