package dev.alllexey.itmowidgets.backend.feature.clients.web

import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientHeader
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientKind
import dev.alllexey.itmowidgets.backend.feature.clients.service.ClientActivityService
import dev.alllexey.itmowidgets.backend.feature.push.model.ClientVersion
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.platform.security.AgentAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import dev.alllexey.itmowidgets.backend.platform.security.WebSessionAuthentication
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Records the client of every authenticated request (ADR 0034). The credential picks the instance: a web session its
 * id (kind web), an agent token its id (kind agent), an app bearer token the header's `install`. Apps and agents from
 * before `X-Client` are recorded from `X-App-Version` (one instance per user and platform) and the agent's
 * `User-Agent`. Anonymous requests are not recorded and a failure never fails the request. Registered after the
 * security chain by `ClientActivityFilterConfig`.
 */
class ClientActivityFilter(private val clients: ClientActivityService) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        try {
            record(request)
        } catch (e: Exception) {
            log.warn("Client not recorded: {}", SafeDiagnostics.describe(e), e)
        }
        filterChain.doFilter(request, response)
    }

    private fun record(request: HttpServletRequest) {
        val authentication = SecurityContextHolder.getContext().authentication ?: return
        if (!authentication.isAuthenticated) return
        val header = ClientHeader.parse(request.getHeader(ClientHeader.HEADER))
        when (authentication) {
            is WebSessionAuthentication -> {
                val session = authentication.sessionId ?: return
                if (header?.kind == ClientKind.WEB) clients.report(authentication.uuid(), ClientKind.WEB, session, header)
            }

            is AgentAuthentication -> {
                val agent = header?.takeIf { it.kind == ClientKind.AGENT }
                    ?: ClientHeader.fromAgentUserAgent(request.getHeader(HttpHeaders.USER_AGENT))
                    ?: return
                clients.report(authentication.userId, ClientKind.AGENT, authentication.tokenId, agent)
            }

            is UsernamePasswordAuthenticationToken -> {
                val userId = authentication.uuid()
                val app = header?.takeIf { it.kind == ClientKind.ANDROID || it.kind == ClientKind.IOS }
                    ?: legacy(request.getHeader(ClientVersion.HEADER))
                    ?: return
                clients.report(userId, app.kind, app.install ?: ClientActivityService.legacyInstance(userId, app.kind), app)
            }
        }
    }

    private fun legacy(value: String?): ClientHeader? = ClientVersion.parse(value)?.let {
        ClientHeader(ClientKind.valueOf(it.platform.name), it.version, it.build.toString(), it.distribution)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ClientActivityFilter::class.java)
    }
}
