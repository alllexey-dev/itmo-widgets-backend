package dev.alllexey.itmowidgets.backend.feature.activity.web

import dev.alllexey.itmowidgets.backend.feature.activity.service.ActiveWeekRecorder
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.platform.security.ConnectionAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Records the activity week of every authenticated request, whatever the credential (ITMO.ID bearer, connection token
 * or `iw_client` cookie) and whether or not the client sends `X-Client`. Registered after the security chain by
 * `ActiveWeekFilterConfig`, so it sees the request's authentication and never sees a request the chain rejected.
 * Anonymous requests are not recorded and a failure never fails the request.
 */
class ActiveWeekFilter(private val recorder: ActiveWeekRecorder) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        try {
            when (val authentication = SecurityContextHolder.getContext().authentication) {
                is ConnectionAuthentication -> recorder.record(authentication.userId)
                is UsernamePasswordAuthenticationToken -> if (authentication.isAuthenticated) recorder.record(authentication.uuid())
                else -> Unit
            }
        } catch (e: Exception) {
            log.warn("Activity week not recorded: {}", SafeDiagnostics.describe(e), e)
        }
        filterChain.doFilter(request, response)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ActiveWeekFilter::class.java)
    }
}
