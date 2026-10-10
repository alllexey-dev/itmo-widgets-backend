package dev.alllexey.itmowidgets.backend.platform.security

import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.platform.error.ErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
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
 * Authenticates a request by the `iw_session` cookie when no bearer token was presented.
 * Cookie-authenticated requests other than GET/HEAD must carry `X-Web-Request: 1` (CSRF guard on top of SameSite=Strict).
 * Admin and moderation routes take only a session signed in within the admin max age, otherwise 401 `reauth_required`.
 * Web-login approval and the anonymous challenge routes never use the cookie.
 */
@Component
class WebSessionFilter(private val sessions: WebSessionService, private val jsonMapper: JsonMapper) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (SecurityContextHolder.getContext().authentication == null && !hasBearer(request) && !isUnder(request, COOKIE_FREE_PREFIXES)) {
            val session = WebSessionCookie.read(request)?.let(sessions::resolve)
            if (session != null) {
                if (!session.freshForAdmin && isUnder(request, PRIVILEGED_PREFIXES)) {
                    requireReauth(response)
                    return
                }
                if (request.method !in SAFE_METHODS && request.getHeader(WEB_REQUEST_HEADER) != "1") {
                    reject(response)
                    return
                }
                SecurityContextHolder.getContext().authentication = WebSessionAuthentication(session.userId, session.signedInAt)
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun hasBearer(request: HttpServletRequest): Boolean = request.getHeader("Authorization")?.startsWith("Bearer ") == true

    private fun isUnder(request: HttpServletRequest, prefixes: List<String>): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return prefixes.any { path == it || path.startsWith("$it/") }
    }

    private fun reject(response: HttpServletResponse) =
        response.writeApiError(jsonMapper, HttpStatus.FORBIDDEN, "$WEB_REQUEST_HEADER: 1 header required", ErrorCode.CSRF)

    private fun requireReauth(response: HttpServletResponse) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        response.writeApiError(jsonMapper, HttpStatus.UNAUTHORIZED, "Sign in again to use admin routes", ErrorCode.REAUTH_REQUIRED)
    }

    companion object {
        const val WEB_REQUEST_HEADER = "X-Web-Request"
        private val SAFE_METHODS = setOf("GET", "HEAD")
        private val COOKIE_FREE_PREFIXES = listOf("/api/users/me/web-login", "/api/web/auth/challenges")
        private val PRIVILEGED_PREFIXES = listOf("/api/admin", "/api/moderation")
    }
}

/** A user authenticated by a web session, distinguishable from an app bearer token; [signedInAt] is the session's creation. */
class WebSessionAuthentication(private val userId: UUID, val signedInAt: Instant) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }
    override fun getCredentials(): Any? = null
    override fun getPrincipal(): Any = userId.toString()
}

object WebSessionCookie {
    const val NAME = "iw_session"

    fun read(request: HttpServletRequest): String? = request.cookies?.firstOrNull { it.name == NAME }?.value

    fun issue(token: String, maxAge: Duration): ResponseCookie = base(token).maxAge(maxAge).build()

    fun clear(): ResponseCookie = base("").maxAge(0).build()

    private fun base(value: String) = ResponseCookie.from(NAME, value)
        .httpOnly(true).secure(true).sameSite("Strict").path("/api")
}
