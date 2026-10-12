package dev.alllexey.itmowidgets.backend.platform.security

import dev.alllexey.itmowidgets.backend.platform.error.ErrorCode
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** A caller authenticated by a personal agent token; [scopes] are the token's wire scope names. */
class AgentAuthentication(val userId: UUID, val tokenId: UUID, val tokenName: String, val scopes: Set<String>) :
    AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = userId.toString()
}

/** What an agent token may do with one request. */
enum class AgentDecision { ALLOWED, DISABLED, DENIED, RATE_LIMITED }

/** Resolves `iwa_` bearer tokens; implemented by the agent feature so `platform` knows no feature. */
interface AgentTokenResolver {
    /** The token's caller, or null when the token is unknown, revoked or expired. */
    fun resolve(token: String): AgentAuthentication?

    /** Access, the route allow-list and the rate limit for [path] (without the context path). */
    fun decide(authentication: AgentAuthentication, method: String, path: String): AgentDecision
}

/**
 * Authenticates `Authorization: Bearer iwa_…` before the ITMO.ID filter, which skips such tokens. An unknown token leaves
 * the request anonymous (401 on protected routes) and never falls back to the cookie. Without a resolver bean (slice
 * tests) agent tokens authenticate nobody.
 */
@Component
class AgentTokenFilter(private val resolver: ObjectProvider<AgentTokenResolver>, private val jsonMapper: JsonMapper) :
    OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val token = agentToken(request)
        val resolver = resolver.ifAvailable
        if (token != null && resolver != null) {
            val authentication = try {
                resolver.resolve(token)
            } catch (e: Exception) {
                log.error("Agent token lookup failed: {}", SafeDiagnostics.describe(e), e)
                response.writeApiError(
                    jsonMapper,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "An internal server error occurred",
                    ErrorCode.INTERNAL_SERVER_ERROR,
                )
                return
            }
            if (authentication != null) {
                val path = request.requestURI.removePrefix(request.contextPath)
                when (resolver.decide(authentication, request.method, path)) {
                    AgentDecision.ALLOWED -> SecurityContextHolder.getContext().authentication = authentication

                    AgentDecision.DISABLED -> return response.writeApiError(
                        jsonMapper,
                        HttpStatus.FORBIDDEN,
                        "Self-hosted clients are turned off for this account",
                        ErrorCode.PERMISSION_DENIED,
                    )

                    AgentDecision.DENIED -> return response.writeApiError(
                        jsonMapper,
                        HttpStatus.FORBIDDEN,
                        "Access denied",
                        ErrorCode.ACCESS_DENIED,
                    )

                    AgentDecision.RATE_LIMITED -> return response.writeApiError(
                        jsonMapper,
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too many requests",
                        ErrorCode.RATE_LIMITED,
                    )
                }
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        const val PREFIX = "iwa_"
        private val log = LoggerFactory.getLogger(AgentTokenFilter::class.java)

        fun agentToken(request: HttpServletRequest): String? =
            request.getHeader("Authorization")?.takeIf { it.startsWith("Bearer $PREFIX") }?.substring("Bearer ".length)
    }
}
