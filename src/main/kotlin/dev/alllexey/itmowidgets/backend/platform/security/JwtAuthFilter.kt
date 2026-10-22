package dev.alllexey.itmowidgets.backend.platform.security

import com.auth0.jwk.JwkException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.platform.error.ErrorCode
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier.Companion.getAuthTime
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier.Companion.getClaimOrNull
import dev.alllexey.itmowidgets.backend.platform.security.ItmoJwtVerifier.Companion.getIsu
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** Whether an ITMO.ID session (`sid`) belongs to a connected client; implemented by the connections feature. */
fun interface ClientSessions {
    fun isClientSession(sid: String): Boolean
}

/**
 * Authenticates a request by its ITMO.ID bearer token. A token that authenticates nobody leaves the request
 * anonymous, so a protected route answers 401; a failure to resolve a verified caller is Backend's own and
 * answers 500 here, because an anonymous request would turn it into a 401. A token of a connected client's own
 * ITMO.ID session is 403 `client_session`, and a user who has not turned the services on gets 403 `not_enrolled`
 * outside the enrollment routes; both leave the `/api/app/` routes anonymous instead.
 */
@Component
class JwtAuthFilter(
    private val itmoJwtVerifier: ItmoJwtVerifier,
    private val userService: UserService,
    private val clientSessions: ObjectProvider<ClientSessions>,
    private val jsonMapper: JsonMapper,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val token = extractJwtFromRequest(request)?.let(::verified)
        val isu = token?.getIsu() ?: return filterChain.doFilter(request, response)
        val path = request.requestURI.removePrefix(request.contextPath)
        val open = path.startsWith(OPEN_PREFIX)
        val userId = try {
            val sid = token.getClaimOrNull(SID)?.asString()
            when {
                sid != null && clientSessions.ifAvailable?.isClientSession(sid) == true -> if (open) {
                    null
                } else {
                    return response.writeApiError(
                        jsonMapper,
                        HttpStatus.FORBIDDEN,
                        "This ITMO.ID session belongs to a connected client; call with its connection token",
                        ErrorCode.CLIENT_SESSION,
                    )
                }

                else -> userService.enrolledIdByIsu(isu) ?: when {
                    ENROLLMENT_ROUTES.any { it.first == request.method && it.second == path } -> userService.resolveIdByIsu(isu)

                    open -> null

                    else -> return response.writeApiError(
                        jsonMapper,
                        HttpStatus.FORBIDDEN,
                        "Turn the ITMO.Widgets services on in the app first",
                        ErrorCode.NOT_ENROLLED,
                    )
                }
            }
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
        if (userId != null) SecurityContextHolder.getContext().authentication = authentication(userId, token.getAuthTime(), request)
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
        // Connection tokens are ConnectionAuthFilter's; they are never verified as ITMO.ID tokens.
        return if (bearerToken != null && bearerToken.startsWith("Bearer ") &&
            !bearerToken.startsWith("Bearer ${ConnectionAuthFilter.PREFIX}")
        ) {
            bearerToken.substring(7)
        } else {
            null
        }
    }

    companion object {
        private const val SID = "sid"
        private const val OPEN_PREFIX = "/api/app/"

        /** The routes that turn the services on, and so create the user. */
        private val ENROLLMENT_ROUTES = listOf("PUT" to "/api/users/me/consent", "POST" to "/api/device/register-device")
        private val log: Logger = LoggerFactory.getLogger(JwtAuthFilter::class.java)
    }
}
