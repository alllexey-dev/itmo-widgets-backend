package dev.alllexey.itmowidgets.backend.platform.security

import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentTokenFilterTest {
    private val chain = mock(FilterChain::class.java)
    private val json = JsonMapper.builder().build()
    private val agent = AgentAuthentication(UUID.randomUUID(), UUID.randomUUID(), "nas", setOf("sport"))
    private var decision = AgentDecision.ALLOWED
    private val resolver = object : AgentTokenResolver {
        override fun resolve(token: String) = agent.takeIf { token == TOKEN }

        override fun decide(authentication: AgentAuthentication, method: String, path: String) = decision
    }
    private val filter =
        AgentTokenFilter(StaticListableBeanFactory(mapOf("resolver" to resolver)).getBeanProvider(AgentTokenResolver::class.java), json)

    @AfterEach
    fun cleanup() = SecurityContextHolder.clearContext()

    @Test
    fun `an allowed agent token authenticates the request`() {
        val request = request(TOKEN)
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertSame(agent, SecurityContextHolder.getContext().authentication)
        assertEquals(agent.userId.toString(), agent.name)
        verify(chain).doFilter(request, response)
    }

    @ParameterizedTest
    @CsvSource("DISABLED,403,permission_denied", "DENIED,403,access_denied", "RATE_LIMITED,429,rate_limited")
    fun `a refused agent token never reaches the chain`(refusal: AgentDecision, status: Int, code: String) {
        decision = refusal
        val request = request(TOKEN)
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertEquals(status, response.status)
        assertTrue(response.contentAsString.contains("\"code\":\"$code\""), response.contentAsString)
        assertNull(SecurityContextHolder.getContext().authentication)
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `an unknown agent token stays anonymous and ITMO_ID never checks it`() {
        val request = request("iwa_unknown")
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, chain)
        assertNull(SecurityContextHolder.getContext().authentication)
        verify(chain).doFilter(request, response)

        val verifier = mock(ItmoJwtVerifier::class.java)
        val users = mock(UserService::class.java)
        JwtAuthFilter(verifier, users, json).doFilter(request(TOKEN), MockHttpServletResponse(), chain)
        verifyNoInteractions(verifier, users)
    }

    @Test
    fun `without a resolver bean agent tokens authenticate nobody`() {
        val bare = AgentTokenFilter(StaticListableBeanFactory().getBeanProvider(AgentTokenResolver::class.java), json)
        bare.doFilter(request(TOKEN), MockHttpServletResponse(), chain)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    private fun request(token: String) = MockHttpServletRequest("GET", "/api/agent/me").apply {
        requestURI = "/api/agent/me"
        addHeader("Authorization", "Bearer $token")
    }

    private companion object {
        const val TOKEN = "iwa_synthetic"
    }
}
