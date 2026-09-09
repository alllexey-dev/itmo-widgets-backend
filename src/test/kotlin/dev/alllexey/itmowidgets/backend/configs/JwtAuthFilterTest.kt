package dev.alllexey.itmowidgets.backend.configs

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.alllexey.itmowidgets.backend.services.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.services.UserService
import jakarta.servlet.FilterChain
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService

class JwtAuthFilterTest {
    private val verifier = mock(ItmoJwtVerifier::class.java)
    private val details = mock(UserDetailsService::class.java)
    private val users = mock(UserService::class.java)
    private val chain = mock(FilterChain::class.java)
    private val filter = JwtAuthFilter(verifier, details, users)
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
        val request = MockHttpServletRequest("GET", "/api/device/current").apply {
            addHeader("Authorization", "Bearer $secret")
        }
        val response = MockHttpServletResponse()
        `when`(verifier.verifyAndDecode(secret)).thenThrow(IllegalStateException(secret, IllegalArgumentException(nested)))

        filter.doFilter(request, response, chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        verifyNoInteractions(users, details)
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
    fun `request without bearer credentials stays anonymous without contacting authentication services`() {
        val request = MockHttpServletRequest("GET", "/api/app/version-info")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        verifyNoInteractions(verifier, users, details)
        verify(chain).doFilter(request, response)
        assertTrue(logs.list.isEmpty())
    }
}
