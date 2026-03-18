package dev.alllexey.itmowidgets.backend.configs

import dev.alllexey.itmowidgets.backend.services.ItmoJwtVerifier
import dev.alllexey.itmowidgets.backend.services.ItmoJwtVerifier.Companion.getIsu
import dev.alllexey.itmowidgets.backend.services.UserService
import dev.alllexey.itmowidgets.core.utils.AuthenticationException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(
    private val itmoJwtVerifier: ItmoJwtVerifier,
    private val userDetailsService: UserDetailsService,
    private val userService: UserService
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        extractJwtFromRequest(request)?.let { jwt ->
            try {
                val decoded = itmoJwtVerifier.verifyAndDecode(jwt)
                val isu = decoded.getIsu()
                isu?.let {
                    val user = userService.findOrCreateByIsu(it)
                    val userDetails = userDetailsService.loadUserByUsername(user.id.toString())

                    val authentication = UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.authorities
                    ).apply {
                        details = WebAuthenticationDetailsSource().buildDetails(request)
                    }
                    SecurityContextHolder.getContext().authentication = authentication
                }
            } catch (e: Exception) {
                log.error("JWT authentication failed", e)
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun extractJwtFromRequest(request: HttpServletRequest): String? {
        val bearerToken = request.getHeader("Authorization")
        return if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken.substring(7)
        } else null
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(JwtAuthFilter::class.java)
    }
}