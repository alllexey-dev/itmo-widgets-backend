package dev.alllexey.itmowidgets.backend.feature.activity.web

import dev.alllexey.itmowidgets.backend.feature.activity.service.ActiveWeekRecorder
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.platform.security.BearerAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl
import dev.alllexey.itmowidgets.backend.testing.TestConnections
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class ActiveWeekFilterTest {
    private val recorder = mock(ActiveWeekRecorder::class.java)
    private val chain = mock(FilterChain::class.java)
    private val filter = ActiveWeekFilter(recorder)
    private val user = UUID.randomUUID()

    @BeforeEach
    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `an app bearer request counts without any client header, like the Android apps before X-Client`() {
        SecurityContextHolder.getContext().authentication = BearerAuthentication(UserDetailsServiceImpl.principal(user), null)

        filter.doFilter(request(), MockHttpServletResponse(), chain)

        verify(recorder).record(user)
    }

    @Test
    fun `a connection token and a web cookie count for their account`() {
        SecurityContextHolder.getContext().authentication =
            TestConnections.client(user, ClientType.AGENT, setOf(ConnectionScope.SPORT))
        filter.doFilter(request(), MockHttpServletResponse(), chain)
        val webUser = UUID.randomUUID()
        SecurityContextHolder.getContext().authentication = TestConnections.web(webUser, Instant.EPOCH)
        filter.doFilter(request(), MockHttpServletResponse(), chain)

        verify(recorder).record(user)
        verify(recorder).record(webUser)
    }

    @Test
    fun `anonymous requests record nothing`() {
        filter.doFilter(request(), MockHttpServletResponse(), chain)
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
        filter.doFilter(request(), MockHttpServletResponse(), chain)

        verifyNoInteractions(recorder)
    }

    @Test
    fun `a failure to record never fails the request`() {
        SecurityContextHolder.getContext().authentication = BearerAuthentication(UserDetailsServiceImpl.principal(user), null)
        doThrow(DataAccessResourceFailureException("synthetic")).`when`(recorder).record(user)
        val request = request()
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
        assertEquals(200, response.status)
    }

    private fun request() = MockHttpServletRequest("GET", "/api/users/me/privacy")
}
