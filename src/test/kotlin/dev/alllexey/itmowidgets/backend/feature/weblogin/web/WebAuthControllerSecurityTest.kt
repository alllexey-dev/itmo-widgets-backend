package dev.alllexey.itmowidgets.backend.feature.weblogin.web

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.feature.social.web.FriendController
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.model.UserSettingsEntity
import dev.alllexey.itmowidgets.backend.feature.users.service.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserProfileService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.users.web.GroupData
import dev.alllexey.itmowidgets.backend.feature.users.web.UserCapabilities
import dev.alllexey.itmowidgets.backend.feature.users.web.UserController
import dev.alllexey.itmowidgets.backend.feature.users.web.UserData
import dev.alllexey.itmowidgets.backend.feature.users.web.UserPrivacySettings
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebLoginService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.platform.error.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.platform.error.TooManyRequestsException
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real JWT and web session filters; only the services behind them are mocked. */
@WebMvcTest(WebAuthController::class, UserController::class, FriendController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class)
class WebAuthControllerSecurityTest @Autowired constructor(private val mvc: MockMvc, private val json: ObjectMapper) {
    @MockitoBean private lateinit var verifier: ItmoJwtVerifier

    @MockitoBean private lateinit var users: UserService

    @MockitoBean private lateinit var webSessions: WebSessionService

    @MockitoBean private lateinit var webLogins: WebLoginService

    @MockitoBean private lateinit var access: AdminAccess

    @MockitoBean private lateinit var privacy: UserPrivacyService

    @MockitoBean private lateinit var currentGroups: CurrentStudyGroupsService

    @MockitoBean private lateinit var profiles: UserProfileService

    @MockitoBean private lateinit var restrictions: RestrictionService
    private val user = User(isu = ISU, pictureUrl = null, name = "Synthetic user", createdAt = NOW).apply {
        settings = UserSettingsEntity(user = this)
    }
    private val challengeId = UUID.randomUUID()

    @BeforeEach
    fun fixture() {
        `when`(verifier.verifyAccessToken(BEARER)).thenReturn(JWT.decode(BEARER))
        `when`(users.resolveIdByIsu(ISU)).thenReturn(user.id)
        `when`(webSessions.resolve(SESSION)).thenReturn(user.id)
        `when`(users.findUserById(user.id)).thenReturn(user)
        `when`(users.updatePrivacySettings(user, PRIVACY)).thenReturn(PRIVACY)
        `when`(privacy.userDataFor(user, user)).thenReturn(DATA)
        `when`(currentGroups.userData(DATA)).thenReturn(DATA)
        `when`(access.rolesOf(user.id)).thenReturn(setOf(UserRole.ADMIN, UserRole.MODERATOR))
    }

    @Test
    fun `anonymous callers reach only challenge creation and polling`() {
        `when`(webLogins.createChallenge(null, "127.0.0.1")).thenReturn(CreatedChallenge(challengeId, "ABCD2345", "secret", NOW))
        `when`(webLogins.claim(challengeId, "secret")).thenReturn(ClaimResult.Pending)
        mvc.perform(post("/api/web/auth/challenges")).andExpect(status().isOk)
        mvc.perform(get("/api/web/auth/challenges/$challengeId").header(POLL, "secret")).andExpect(status().isOk)

        for (request in listOf(
            post("/api/web/auth/logout"), get("/api/web/auth/me"), get("/api/users/me/roles"),
            get("/api/users/me/web-login/ABCD2345"), post("/api/users/me/web-login/$challengeId/approve"),
            get("/api/users/me/data"), get("/api/friends"), get("/api/web/auth/challenges"),
            put("/api/web/auth/challenges/$challengeId"), post("/api/web/auth/challenges/$challengeId"),
        )) {
            mvc.perform(request).andExpect(status().isUnauthorized)
        }
        verify(webLogins, never()).approve(user.id, challengeId)
        verifyNoInteractions(access, profiles)
    }

    @Test
    fun `a created challenge has exact keys and the proxy address counts only behind the proxy`() {
        `when`(
            webLogins.createChallenge("Synthetic browser", "203.0.113.7"),
        ).thenReturn(CreatedChallenge(challengeId, "ABCD2345", "secret", NOW))
        `when`(
            webLogins.createChallenge("Synthetic browser", "198.51.100.9"),
        ).thenThrow(TooManyRequestsException("Too many login codes, try again later"))
        val body = mvc.perform(
            post("/api/web/auth/challenges").header(HttpHeaders.USER_AGENT, "Synthetic browser")
                .header("X-Real-IP", "203.0.113.7").header("X-Forwarded-For", "192.0.2.1, 203.0.113.7").with {
                    it.remoteAddr = "172.18.0.5"
                    it
                },
        )
            .andExpect(status().isOk).andReturn().response.contentAsString
        val data = json.readTree(body)["data"]
        assertEquals(setOf("id", "code", "pollSecret", "expiresAt"), data.keys())
        assertEquals("2026-09-24T09:02:00Z", data["expiresAt"].stringValue())

        // A direct public peer cannot choose its address by header.
        mvc.perform(
            post("/api/web/auth/challenges").header(HttpHeaders.USER_AGENT, "Synthetic browser")
                .header("X-Real-IP", "203.0.113.7").with {
                    it.remoteAddr = "198.51.100.9"
                    it
                },
        )
            .andExpect(status().isTooManyRequests).andExpect(jsonPath("$.error.code").value("rate_limited"))
    }

    @Test
    fun `only the approving poll sets the session cookie with strict attributes`() {
        `when`(webLogins.claim(challengeId, "secret")).thenReturn(ClaimResult.Pending, ClaimResult.Approved(SESSION), ClaimResult.Expired)
        val poll = get("/api/web/auth/challenges/$challengeId").header(POLL, "secret")
        mvc.perform(poll).andExpect(status().isOk).andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        val cookie = mvc.perform(poll).andExpect(status().isOk).andExpect(jsonPath("$.data.status").value("APPROVED"))
            .andReturn().response.getHeader(HttpHeaders.SET_COOKIE)!!
        assertTrue(cookie.startsWith("iw_session=$SESSION;"), cookie)
        for (attribute in listOf("Path=/api", "Max-Age=5184000", "Secure", "HttpOnly", "SameSite=Strict")) {
            assertTrue(cookie.split("; ").contains(attribute), "$attribute in $cookie")
        }
        mvc.perform(poll).andExpect(status().isOk).andExpect(jsonPath("$.data.status").value("EXPIRED"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        mvc.perform(get("/api/web/auth/challenges/$challengeId")).andExpect(status().isBadRequest)
    }

    @Test
    fun `the session cookie authenticates reads of own data and friends`() {
        mvc.perform(get("/api/users/me/data").cookie(COOKIE)).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.isu").value(ISU))
        mvc.perform(get("/api/friends").cookie(COOKIE)).andExpect(status().isOk).andExpect(jsonPath("$.data").isEmpty)
        verify(profiles).friends(user.id)
        `when`(webSessions.resolve("expired-session")).thenReturn(null)
        mvc.perform(get("/api/users/me/data").cookie(Cookie("iw_session", "expired-session"))).andExpect(status().isUnauthorized)
    }

    @Test
    fun `a cookie mutation needs the web request header`() {
        val update = put("/api/users/me/privacy").contentType(MediaType.APPLICATION_JSON).content(PRIVACY_BODY).cookie(COOKIE)
        mvc.perform(update).andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("csrf"))
        mvc.perform(update.header("X-Web-Request", "0")).andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("csrf"))
        verify(users, never()).updatePrivacySettings(user, PRIVACY)

        mvc.perform(
            put("/api/users/me/privacy").contentType(MediaType.APPLICATION_JSON).content(PRIVACY_BODY).cookie(COOKIE)
                .header("X-Web-Request", "1"),
        ).andExpect(status().isOk).andExpect(jsonPath("$.data.friendsVisibility").value("ALL"))
        verify(users).updatePrivacySettings(user, PRIVACY)
    }

    @Test
    fun `bearer requests need no web header and win over a cookie`() {
        clearInvocations(webSessions)
        mvc.perform(put("/api/users/me/privacy").contentType(MediaType.APPLICATION_JSON).content(PRIVACY_BODY).bearer())
            .andExpect(status().isOk)
        mvc.perform(put("/api/users/me/privacy").contentType(MediaType.APPLICATION_JSON).content(PRIVACY_BODY).bearer().cookie(COOKIE))
            .andExpect(status().isOk)
        mvc.perform(get("/api/users/me/data").bearer()).andExpect(status().isOk)
        verifyNoInteractions(webSessions)
        // An invalid bearer is not replaced by the cookie.
        `when`(verifier.verifyAccessToken("broken")).thenThrow(JWTVerificationException("invalid"))
        mvc.perform(get("/api/users/me/data").header(HttpHeaders.AUTHORIZATION, "Bearer broken").cookie(COOKIE))
            .andExpect(status().isUnauthorized)
        verifyNoInteractions(webSessions)
    }

    @Test
    fun `web login is approved only with the app bearer token`() {
        val preview = WebLoginPreview(challengeId, "Synthetic browser", NOW.minusSeconds(120), NOW)
        `when`(webLogins.preview(user.id, "ABCD2345")).thenReturn(preview)
        for (request in listOf(get("/api/users/me/web-login/ABCD2345"), post("/api/users/me/web-login/$challengeId/approve"))) {
            mvc.perform(request.cookie(COOKIE)).andExpect(status().isUnauthorized)
            mvc.perform(request.cookie(COOKIE).header("X-Web-Request", "1")).andExpect(status().isUnauthorized)
        }
        verifyNoInteractions(webLogins)

        val body = mvc.perform(get("/api/users/me/web-login/ABCD2345").bearer()).andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertEquals(setOf("challengeId", "userAgent", "createdAt", "expiresAt"), json.readTree(body)["data"].keys())
        mvc.perform(post("/api/users/me/web-login/$challengeId/approve").bearer()).andExpect(status().isOk)
        verify(webLogins).approve(user.id, challengeId)
    }

    @Test
    fun `me and my roles list the roles of a cookie or bearer user`() {
        val body = mvc.perform(get("/api/web/auth/me").cookie(COOKIE)).andExpect(status().isOk).andReturn().response.contentAsString
        val data = json.readTree(body)["data"]
        assertEquals(setOf("isu", "name", "pictureUrl", "groups", "roles"), data.keys())
        assertEquals(listOf("MODERATOR", "ADMIN"), data["roles"].values().map { it.stringValue() })
        assertEquals("P3219", data["groups"][0]["name"].stringValue())
        mvc.perform(get("/api/users/me/roles").bearer()).andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0]").value("MODERATOR")).andExpect(jsonPath("$.data[1]").value("ADMIN"))
    }

    @Test
    fun `logout by cookie needs the web header revokes the session and clears the cookie`() {
        mvc.perform(post("/api/web/auth/logout").cookie(COOKIE))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("csrf"))
        verify(webSessions, never()).revoke(SESSION)
        val cookie = mvc.perform(post("/api/web/auth/logout").cookie(COOKIE).header("X-Web-Request", "1"))
            .andExpect(status().isOk).andReturn().response.getHeader(HttpHeaders.SET_COOKIE)!!
        verify(webSessions).revoke(SESSION)
        assertTrue(cookie.startsWith("iw_session=;"), cookie)
        assertTrue(cookie.split("; ").containsAll(listOf("Path=/api", "Max-Age=0", "Secure", "HttpOnly", "SameSite=Strict")), cookie)
    }

    private fun MockHttpServletRequestBuilder.bearer() = header(HttpHeaders.AUTHORIZATION, "Bearer $BEARER")

    private fun JsonNode.keys(): Set<String> = propertyNames().toSet()

    private companion object {
        const val ISU = 970001
        const val POLL = "X-Poll-Secret"
        const val SESSION = "synthetic-session-token"
        val BEARER: String = JWT.create().withClaim("isu", ISU).sign(Algorithm.none())
        val COOKIE = Cookie("iw_session", SESSION)
        val NOW: Instant = Instant.parse("2026-09-24T09:02:00Z")
        val PRIVACY = UserPrivacySettings(SharingVisibility.NOBODY, SharingVisibility.FRIENDS, SharingVisibility.ALL)
        const val PRIVACY_BODY = """{"scheduleVisibility":"NOBODY","sportVisibility":"FRIENDS","friendsVisibility":"ALL"}"""
        val DATA = UserData(ISU, "Synthetic user", null, listOf(GroupData("P3219", 2, "ФПИиКТ")), UserCapabilities(true, true, true))
    }
}
