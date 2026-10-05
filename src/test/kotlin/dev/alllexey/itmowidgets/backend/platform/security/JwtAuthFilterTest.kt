package dev.alllexey.itmowidgets.backend.platform.security

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.auth0.jwk.NetworkException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtAuthFilterTest {
    private val verifier = mock(ItmoJwtVerifier::class.java)
    private val users = mock(UserService::class.java)
    private val chain = mock(FilterChain::class.java)
    private val json = JsonMapper.builder().build()
    private val filter = JwtAuthFilter(verifier, users, json)
    private val logger = LoggerFactory.getLogger(JwtAuthFilter::class.java) as Logger
    private lateinit var logs: ListAppender<ILoggingEvent>

    @BeforeEach
    fun prepare() {
        SecurityContextHolder.clearContext()
        logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
    }

    @AfterEach
    fun cleanup() {
        SecurityContextHolder.clearContext()
        logger.detachAppender(logs)
        logs.stop()
    }

    @Test
    fun `invalid JWT neither authenticates nor logs the token or provider exception`() {
        val secret = "synthetic-private-access-token"
        val nested = "synthetic-provider-payload"
        val request = bearer(secret)
        val response = MockHttpServletResponse()
        `when`(verifier.verifyAccessToken(secret)).thenThrow(JWTVerificationException(secret, IllegalArgumentException(nested)))

        filter.doFilter(request, response, chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        verifyNoInteractions(users)
        verify(chain).doFilter(request, response)
        assertTrue(logs.list.isNotEmpty())
        logs.list.forEach { event ->
            assertNull(event.throwableProxy)
            val text = event.formattedMessage + event.message + event.argumentArray.orEmpty().joinToString()
            assertFalse(text.contains(secret))
            assertFalse(text.contains(nested))
        }
    }

    @Test
    fun `an unreachable key set leaves the request anonymous`() {
        val request = bearer(TOKEN)
        val response = MockHttpServletResponse()
        // A checked exception from Java's side: Kotlin declares none, so Mockito's thenThrow would refuse it.
        doAnswer { throw NetworkException("synthetic outage", null) }.`when`(verifier).verifyAccessToken(TOKEN)

        filter.doFilter(request, response, chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        verifyNoInteractions(users)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `a verified token authenticates the resolved user id without loading the user`() {
        val request = bearer(TOKEN)
        val response = MockHttpServletResponse()
        `when`(verifier.verifyAccessToken(TOKEN)).thenReturn(JWT.decode(TOKEN))
        `when`(users.resolveIdByIsu(ISU)).thenReturn(USER_ID)

        filter.doFilter(request, response, chain)

        assertEquals(USER_ID.toString(), SecurityContextHolder.getContext().authentication!!.name)
        verify(users).resolveIdByIsu(ISU)
        verify(users, never()).findOrCreateByIsu(ISU)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `a database failure in the identity lookup is a 500, not an anonymous request`() {
        val request = bearer(TOKEN)
        val response = MockHttpServletResponse()
        `when`(verifier.verifyAccessToken(TOKEN)).thenReturn(JWT.decode(TOKEN))
        `when`(users.resolveIdByIsu(ISU)).thenThrow(DataAccessResourceFailureException("synthetic connection failure"))

        filter.doFilter(request, response, chain)

        assertEquals(500, response.status)
        val body = json.readTree(response.contentAsString)
        assertFalse(body["success"].booleanValue())
        assertEquals("internal_server_error", body["error"]["code"].stringValue())
        assertNull(SecurityContextHolder.getContext().authentication)
        verify(chain, never()).doFilter(any(), any())
        assertFalse(response.contentAsString.contains("synthetic connection failure"))
    }

    @Test
    fun `request without bearer credentials stays anonymous without contacting authentication services`() {
        val request = MockHttpServletRequest("GET", "/api/app/version-info")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        verifyNoInteractions(verifier, users)
        verify(chain).doFilter(request, response)
        assertTrue(logs.list.isEmpty())
    }

    private fun bearer(token: String) = MockHttpServletRequest("GET", "/api/device/current").apply {
        addHeader("Authorization", "Bearer $token")
    }

    private companion object {
        const val ISU = 970001
        val USER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000970001")
        val TOKEN: String = JWT.create().withClaim("isu", ISU).sign(Algorithm.none())
    }
}
