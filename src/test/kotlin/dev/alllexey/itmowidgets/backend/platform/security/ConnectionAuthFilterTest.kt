package dev.alllexey.itmowidgets.backend.platform.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionAuthFilterTest {
    private val chain = mock(FilterChain::class.java)
    private val json = JsonMapper.builder().build()
    private val agent = ConnectionAuthentication(UUID.randomUUID(), UUID.randomUUID(), "agent", "nas", setOf("sport"), Instant.EPOCH, false)
    private val web = ConnectionAuthentication(UUID.randomUUID(), UUID.randomUUID(), "web", "Chrome", setOf("user"), Instant.EPOCH, true)
    private val staleWeb = ConnectionAuthentication(web.userId, UUID.randomUUID(), "web", "Chrome", setOf("user"), Instant.EPOCH, false)
    private var decision = ConnectionDecision.ALLOWED
    private val resolver = object : ConnectionResolver {
        override fun resolve(token: String) = mapOf(TOKEN to agent, WEB to web, STALE to staleWeb)[token]

        override fun decide(authentication: ConnectionAuthentication, method: String, path: String) = decision
    }
    private val filter =
        ConnectionAuthFilter(StaticListableBeanFactory(mapOf("resolver" to resolver)).getBeanProvider(ConnectionResolver::class.java), json)

    @AfterEach
    fun cleanup() = SecurityContextHolder.clearContext()

    @Test
    fun `an allowed connection token authenticates the request as its user`() {
        val request = bearer(TOKEN)
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertSame(agent, SecurityContextHolder.getContext().authentication)
        assertEquals(agent.userId.toString(), agent.name)
        verify(chain).doFilter(request, response)
    }

    @ParameterizedTest
    @CsvSource("DISABLED,403,permission_denied", "DENIED,403,access_denied", "RATE_LIMITED,429,rate_limited")
    fun `a refused connection never reaches the chain`(refusal: ConnectionDecision, status: Int, code: String) {
        decision = refusal
        val request = bearer(TOKEN)
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertEquals(status, response.status)
        assertTrue(response.contentAsString.contains("\"code\":\"$code\""), response.contentAsString)
        assertNull(SecurityContextHolder.getContext().authentication)
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `the cookie needs the web header to change data and a fresh approval for admin routes`() {
        val read = cookie(WEB, "GET", "/api/users/me/data")
        filter.doFilter(read, MockHttpServletResponse(), chain)
        assertSame(web, SecurityContextHolder.getContext().authentication)
        SecurityContextHolder.clearContext()

        val write = MockHttpServletResponse()
        filter.doFilter(cookie(WEB, "POST", "/api/friends/1/request"), write, chain)
        assertEquals(403, write.status)
        assertTrue(write.contentAsString.contains("\"code\":\"csrf\""))

        val admin = MockHttpServletResponse()
        filter.doFilter(cookie(STALE, "GET", "/api/admin/dashboard"), admin, chain)
        assertEquals(401, admin.status)
        assertTrue(admin.contentAsString.contains("\"code\":\"reauth_required\""))
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `a bearer wins over the cookie, the pairing routes take no credential and an unknown token stays anonymous`() {
        val both = cookie(WEB, "GET", "/api/users/me/data").apply { addHeader("Authorization", "Bearer itmo-access-token") }
        filter.doFilter(both, MockHttpServletResponse(), chain)
        assertNull(SecurityContextHolder.getContext().authentication)

        filter.doFilter(cookie(WEB, "POST", "/api/pair/challenges"), MockHttpServletResponse(), chain)
        filter.doFilter(bearer(TOKEN).apply { requestURI = "/api/pair/challenges/1/claim" }, MockHttpServletResponse(), chain)
        assertNull(SecurityContextHolder.getContext().authentication)

        filter.doFilter(bearer("iwc_unknown"), MockHttpServletResponse(), chain)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `without a resolver bean connection tokens authenticate nobody`() {
        val bare = ConnectionAuthFilter(StaticListableBeanFactory().getBeanProvider(ConnectionResolver::class.java), json)
        bare.doFilter(bearer(TOKEN), MockHttpServletResponse(), chain)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    private fun bearer(token: String) = MockHttpServletRequest("GET", "/api/client/me").apply {
        addHeader("Authorization", "Bearer $token")
    }

    private fun cookie(token: String, method: String, path: String) = MockHttpServletRequest(method, path).apply {
        setCookies(Cookie(ClientCookie.NAME, token))
    }

    private companion object {
        const val TOKEN = "iwc_synthetic-agent"
        const val WEB = "iwc_synthetic-web"
        const val STALE = "iwc_synthetic-stale-web"
    }
}
