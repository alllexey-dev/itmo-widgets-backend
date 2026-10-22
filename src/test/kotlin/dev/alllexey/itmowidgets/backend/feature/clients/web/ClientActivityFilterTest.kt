package dev.alllexey.itmowidgets.backend.feature.clients.web

import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientHeader
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientKind
import dev.alllexey.itmowidgets.backend.feature.clients.service.ClientActivityService
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl
import dev.alllexey.itmowidgets.backend.testing.TestConnections
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class ClientActivityFilterTest {
    private val clients = mock(ClientActivityService::class.java)
    private val chain = mock(FilterChain::class.java)
    private val filter = ClientActivityFilter(clients)
    private val user = UUID.randomUUID()
    private val install = UUID.randomUUID()

    @BeforeEach
    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `an app bearer request is keyed by its install id`() {
        signInWithBearer()
        val request = request(xClient = "kind=android; version=2.3.0; build=20300; channel=play; os=36; install=$install")

        filter.doFilter(request, MockHttpServletResponse(), chain)

        verify(clients).report(user, ClientKind.ANDROID, install, ClientHeader.parse(request.getHeader(ClientHeader.HEADER))!!)
    }

    @Test
    fun `an app from before X-Client is one legacy instance per user and platform`() {
        signInWithBearer()

        filter.doFilter(request(appVersion = "2.2.1 (8); ios; appstore"), MockHttpServletResponse(), chain)

        verify(clients).report(
            user,
            ClientKind.IOS,
            ClientActivityService.legacyInstance(user, ClientKind.IOS),
            ClientHeader(ClientKind.IOS, "2.2.1", "8", "appstore"),
        )
    }

    @Test
    fun `a connection is keyed by its id under its client's kind`() {
        val session = UUID.randomUUID()
        SecurityContextHolder.getContext().authentication = TestConnections.web(user, Instant.EPOCH, id = session)
        filter.doFilter(request(xClient = "kind=web; version=abc123; channel=web; browser=chrome/131"), MockHttpServletResponse(), chain)
        verify(
            clients,
        ).report(user, ClientKind.WEB, session, ClientHeader(ClientKind.WEB, "abc123", channel = "web", browser = "chrome/131"))

        val token = UUID.randomUUID()
        SecurityContextHolder.getContext().authentication =
            TestConnections.client(user, ClientType.AGENT, setOf(ConnectionScope.SPORT), id = token)
        filter.doFilter(request(userAgent = "itmo-widgets-agent/1.2.0"), MockHttpServletResponse(), chain)
        verify(clients).report(user, ClientKind.AGENT, token, ClientHeader(ClientKind.AGENT, "1.2.0"))

        val mcp = UUID.randomUUID()
        SecurityContextHolder.getContext().authentication =
            TestConnections.client(user, ClientType.MCP, setOf(ConnectionScope.LINKS_READ), id = mcp)
        filter.doFilter(request(xClient = "kind=mcp; version=0.4.0; source=alllexey-dev/itmo-mcp"), MockHttpServletResponse(), chain)
        verify(clients).report(user, ClientKind.MCP, mcp, ClientHeader(ClientKind.MCP, "0.4.0", source = "alllexey-dev/itmo-mcp"))
    }

    @Test
    fun `anonymous requests and a kind the credential cannot be record nothing`() {
        filter.doFilter(request(xClient = "kind=android; version=2.3.0; install=$install"), MockHttpServletResponse(), chain)
        SecurityContextHolder.getContext().authentication = TestConnections.web(user, Instant.EPOCH)
        filter.doFilter(request(xClient = "kind=android; version=2.3.0; install=$install"), MockHttpServletResponse(), chain)
        signInWithBearer()
        filter.doFilter(request(xClient = "kind=web; version=abc123"), MockHttpServletResponse(), chain)
        filter.doFilter(request(), MockHttpServletResponse(), chain)

        verifyNoInteractions(clients)
    }

    @Test
    fun `a failure to record never fails the request`() {
        signInWithBearer()
        doThrow(DataAccessResourceFailureException("synthetic")).`when`(clients).report(
            any(UUID::class.java) ?: user,
            any(ClientKind::class.java) ?: ClientKind.ANDROID,
            any(UUID::class.java) ?: user,
            any(ClientHeader::class.java) ?: ClientHeader(ClientKind.ANDROID, "1"),
        )
        val request = request(xClient = "kind=android; version=2.3.0; install=$install")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
        assertEquals(200, response.status)
    }

    private fun signInWithBearer() {
        val principal = UserDetailsServiceImpl.principal(user)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
    }

    private fun request(xClient: String? = null, appVersion: String? = null, userAgent: String? = null) =
        MockHttpServletRequest("GET", "/api/users/me/privacy").apply {
            if (xClient != null) addHeader(ClientHeader.HEADER, xClient)
            if (appVersion != null) addHeader("X-App-Version", appVersion)
            if (userAgent != null) addHeader("User-Agent", userAgent)
        }
}
