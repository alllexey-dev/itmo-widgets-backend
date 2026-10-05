package dev.alllexey.itmowidgets.backend.platform.security

import dev.alllexey.itmowidgets.backend.platform.error.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import tools.jackson.databind.json.JsonMapper

/**
 * A protected route called without valid credentials (no bearer, an invalid or expired one, no web session):
 * 401 `unauthorized`. Denials of an authenticated caller never come here and keep their 403.
 */
class UnauthorizedEntryPoint(private val jsonMapper: JsonMapper) : AuthenticationEntryPoint {
    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        response.writeApiError(jsonMapper, HttpStatus.UNAUTHORIZED, "Authentication required", ErrorCode.UNAUTHORIZED)
    }
}
