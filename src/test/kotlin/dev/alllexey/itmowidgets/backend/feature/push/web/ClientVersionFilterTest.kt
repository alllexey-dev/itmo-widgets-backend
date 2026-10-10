package dev.alllexey.itmowidgets.backend.feature.push.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.push.model.ClientVersion
import dev.alllexey.itmowidgets.backend.feature.push.service.ClientVersionService
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl
import dev.alllexey.itmowidgets.backend.platform.security.WebSessionAuthentication
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ClientVersionFilterTest {
    private val clientVersions = mock(ClientVersionService::class.java)
    private val chain = mock(FilterChain::class.java)
    private val filter = ClientVersionFilter(clientVersions)
    private val user = UUID.randomUUID()
    private val logger = LoggerFactory.getLogger(ClientVersionFilter::class.java) as Logger
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
    fun `a bearer request reports its build and continues`() {
        signInWithBearer()
        val request = request(header = HEADER)
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        verify(clientVersions).report(user, VERSION)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `anonymous web session malformed and headerless requests report nothing`() {
        filter.doFilter(request(header = HEADER), MockHttpServletResponse(), chain)

        SecurityContextHolder.getContext().authentication = WebSessionAuthentication(user, Instant.EPOCH)
        filter.doFilter(request(header = HEADER), MockHttpServletResponse(), chain)

        signInWithBearer()
        filter.doFilter(request(header = "2.3.0-beta.1; android; github"), MockHttpServletResponse(), chain)
        filter.doFilter(request(header = HEADER.padEnd(ClientVersion.MAX_HEADER_LENGTH + 1, 'x')), MockHttpServletResponse(), chain)
        filter.doFilter(request(header = null), MockHttpServletResponse(), chain)

        verifyNoInteractions(clientVersions)
    }

    @Test
    fun `registration is left to the device service, which knows the device`() {
        signInWithBearer()

        filter.doFilter(request(HEADER, "POST", "/api/device/register-device"), MockHttpServletResponse(), chain)

        verifyNoInteractions(clientVersions)
        filter.doFilter(request(HEADER, "DELETE", "/api/device/current"), MockHttpServletResponse(), chain)
        verify(clientVersions).report(user, VERSION)
    }

    @Test
    fun `a failure to record never fails the request and logs no user data`() {
        signInWithBearer()
        doThrow(DataAccessResourceFailureException("synthetic-database-detail $user")).`when`(clientVersions)
            .report(any(UUID::class.java) ?: user, any(ClientVersion::class.java) ?: VERSION)
        val request = request(header = HEADER)
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
        assertEquals(200, response.status)
        assertEquals(1, logs.list.size)
        logs.list.forEach {
            val text = it.formattedMessage + it.argumentArray.orEmpty().joinToString()
            assertFalse(text.contains(user.toString()) || text.contains("synthetic-database-detail"), text)
        }
    }

    private fun signInWithBearer() {
        val principal = UserDetailsServiceImpl.principal(user)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
    }

    private fun request(header: String?, method: String = "GET", path: String = "/api/users/me/privacy") =
        MockHttpServletRequest(method, path).apply { if (header != null) addHeader(ClientVersion.HEADER, header) }

    private companion object {
        const val HEADER = "2.3.0-beta.1 (20291); android; github"
        val VERSION = ClientVersion("2.3.0-beta.1", 20291, AppPlatform.ANDROID, "github")
    }
}
