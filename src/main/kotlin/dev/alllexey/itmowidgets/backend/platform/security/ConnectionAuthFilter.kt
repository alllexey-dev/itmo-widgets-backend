package dev.alllexey.itmowidgets.backend.platform.security

import dev.alllexey.itmowidgets.backend.platform.error.ErrorCode
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A caller authenticated by a connection token (docs/contracts/connections.md). [client] is the wire client name
 * (`agent`, `mcp`, `web`), [scopes] the wire scope names; [approvedAt] is the last phone approval and [freshForAdmin]
 * whether it is recent enough for admin and moderation routes.
 */
class ConnectionAuthentication(
    val userId: UUID,
    val connectionId: UUID,
    val client: String,
    val connectionName: String,
    val scopes: Set<String>,
    val approvedAt: Instant,
    val freshForAdmin: Boolean,
) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = userId.toString()
}

/** What a connection may do with one request. */
enum class ConnectionDecision { ALLOWED, DISABLED, DENIED, RATE_LIMITED }

/** Resolves `iwc_` tokens; implemented by the connections feature so `platform` knows no feature. */
interface ConnectionResolver {
    /** The token's caller, or null when the token is unknown, revoked or idle. */
    fun resolve(token: String): ConnectionAuthentication?

    /** Access, the route policy and the rate limit for [path] (without the context path). */
    fun decide(authentication: ConnectionAuthentication, method: String, path: String): ConnectionDecision
}

/**
 * Authenticates `Authorization: Bearer iwc_…` (agent, MCP) or, without an `Authorization` header, the `iw_client`
 * cookie (web) before the ITMO.ID filter, which skips such tokens. An unknown token leaves the request anonymous (401
 * on protected routes). Cookie requests other than GET and HEAD need `X-Web-Request: 1`; on admin and moderation
 * routes the cookie needs a recent phone approval, otherwise 401 `reauth_required`. The anonymous pairing routes
 * never use a credential. Without a resolver bean (slice tests) connection tokens authenticate nobody.
 */
@Component
class ConnectionAuthFilter(private val resolver: ObjectProvider<ConnectionResolver>, private val jsonMapper: JsonMapper) :
    OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val path = request.requestURI.removePrefix(request.contextPath)
        val bearer = connectionBearer(request)
        val cookie = if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) ClientCookie.read(request) else null
        val resolver = resolver.ifAvailable
        val token = bearer ?: cookie
        if (token == null || resolver == null || isUnder(path, ANONYMOUS_PREFIXES)) return filterChain.doFilter(request, response)
        val authentication = try {
            resolver.resolve(token)
        } catch (e: Exception) {
            log.error("Connection token lookup failed: {}", SafeDiagnostics.describe(e), e)
            return response.writeApiError(
                jsonMapper,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An internal server error occurred",
                ErrorCode.INTERNAL_SERVER_ERROR,
            )
        }
        if (authentication != null) {
            if (bearer == null) {
                if (!authentication.freshForAdmin && isUnder(path, PRIVILEGED_PREFIXES)) {
                    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    return response.writeApiError(
                        jsonMapper,
                        HttpStatus.UNAUTHORIZED,
                        "Confirm this browser on the phone again to use admin routes",
                        ErrorCode.REAUTH_REQUIRED,
                    )
                }
                if (request.method !in SAFE_METHODS && request.getHeader(WEB_REQUEST_HEADER) != "1") {
                    return response.writeApiError(
                        jsonMapper,
                        HttpStatus.FORBIDDEN,
                        "$WEB_REQUEST_HEADER: 1 header required",
                        ErrorCode.CSRF,
                    )
                }
            }
            when (resolver.decide(authentication, request.method, path)) {
                ConnectionDecision.ALLOWED -> SecurityContextHolder.getContext().authentication = authentication

                ConnectionDecision.DISABLED -> return response.writeApiError(
                    jsonMapper,
                    HttpStatus.FORBIDDEN,
                    "This kind of client is turned off for this account",
                    ErrorCode.PERMISSION_DENIED,
                )

                ConnectionDecision.DENIED -> return response.writeApiError(
                    jsonMapper,
                    HttpStatus.FORBIDDEN,
                    "Access denied",
                    ErrorCode.ACCESS_DENIED,
                )

                ConnectionDecision.RATE_LIMITED -> return response.writeApiError(
                    jsonMapper,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests",
                    ErrorCode.RATE_LIMITED,
                )
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun isUnder(path: String, prefixes: List<String>): Boolean = prefixes.any { path == it || path.startsWith("$it/") }

    companion object {
        const val PREFIX = "iwc_"
        const val WEB_REQUEST_HEADER = "X-Web-Request"
        private val SAFE_METHODS = setOf("GET", "HEAD")
        private val ANONYMOUS_PREFIXES = listOf("/api/pair/challenges")
        private val PRIVILEGED_PREFIXES = listOf("/api/admin", "/api/moderation")
        private val log = LoggerFactory.getLogger(ConnectionAuthFilter::class.java)

        fun connectionBearer(request: HttpServletRequest): String? =
            request.getHeader(HttpHeaders.AUTHORIZATION)?.takeIf { it.startsWith("Bearer $PREFIX") }?.substring("Bearer ".length)
    }
}

/** The web's connection token: `Path=/api`, never readable by JavaScript. */
object ClientCookie {
    const val NAME = "iw_client"

    /** Browsers cap a cookie at 400 days; the connection's own idle limit ends it earlier. */
    private val MAX_AGE: Duration = Duration.ofDays(400)

    fun read(request: HttpServletRequest): String? = request.cookies?.firstOrNull { it.name == NAME }?.value

    fun issue(token: String): ResponseCookie = base(token).maxAge(MAX_AGE).build()

    fun clear(): ResponseCookie = base("").maxAge(0).build()

    private fun base(value: String) = ResponseCookie.from(NAME, value)
        .httpOnly(true).secure(true).sameSite("Strict").path("/api")
}
