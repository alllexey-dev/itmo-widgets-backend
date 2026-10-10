package dev.alllexey.itmowidgets.backend.platform.security

import com.auth0.jwk.JwkException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.platform.error.ErrorCode
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier.Companion.getAuthTime
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier.Companion.getIsu
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * Authenticates a request by its ITMO.ID bearer token. A token that authenticates nobody leaves the request
 * anonymous, so a protected route answers 401; a failure to resolve a verified caller is Backend's own and
 * answers 500 here, because an anonymous request would turn it into a 401.
 */
@Component
class JwtAuthFilter(
    private val itmoJwtVerifier: ItmoJwtVerifier,
    private val userService: UserService,
    private val jsonMapper: JsonMapper,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val token = extractJwtFromRequest(request)?.let(::verified)
        val isu = token?.getIsu()
        if (isu != null) {
            val userId = try {
                userService.resolveIdByIsu(isu)
            } catch (e: Exception) {
                log.error("JWT identity lookup failed: {}", SafeDiagnostics.describe(e), e)
                response.writeApiError(
                    jsonMapper,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "An internal server error occurred",
                    ErrorCode.INTERNAL_SERVER_ERROR,
                )
                return
            }
            SecurityContextHolder.getContext().authentication = authentication(userId, token.getAuthTime(), request)
        }
        filterChain.doFilter(request, response)
    }

    /** The verified token, or null when it authenticates nobody. */
    private fun verified(jwt: String): DecodedJWT? = try {
        itmoJwtVerifier.verifyAccessToken(jwt)
    } catch (e: JWTVerificationException) {
        rejected(e)
    } catch (e: JwkException) {
        rejected(e)
    }

    private fun rejected(e: Exception): DecodedJWT? {
        // An expired or malformed client token is routine: one line per request, with the
        // cause chain available on demand rather than a stack trace per rejected caller.
        log.warn("JWT authentication failed: {}", SafeDiagnostics.describe(e))
        log.debug("JWT authentication failure detail", e)
        return null
    }

    private fun authentication(userId: UUID, authTime: Instant?, request: HttpServletRequest): BearerAuthentication =
        BearerAuthentication(UserDetailsServiceImpl.principal(userId), authTime).apply {
            details = WebAuthenticationDetailsSource().buildDetails(request)
        }

    private fun extractJwtFromRequest(request: HttpServletRequest): String? {
        val bearerToken = request.getHeader("Authorization")
        return if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken.substring(7)
        } else {
            null
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(JwtAuthFilter::class.java)
    }
}
