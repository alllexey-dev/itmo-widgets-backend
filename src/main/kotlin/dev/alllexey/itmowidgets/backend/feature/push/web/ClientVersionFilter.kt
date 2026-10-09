package dev.alllexey.itmowidgets.backend.feature.push.web

import dev.alllexey.itmowidgets.backend.feature.push.model.ClientVersion
import dev.alllexey.itmowidgets.backend.feature.push.service.ClientVersionService
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Records the `X-App-Version` of app requests authenticated by a bearer token. Anonymous and web session requests
 * and malformed headers are ignored, and a failure to record never fails the request. Registered after the
 * security filter chain by `ClientVersionFilterConfig`, not as a component, so `@WebMvcTest` slices leave it out.
 */
class ClientVersionFilter(private val clientVersions: ClientVersionService) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val version = read(request)
        val authentication = SecurityContextHolder.getContext().authentication
        if (version != null && authentication is UsernamePasswordAuthenticationToken && authentication.isAuthenticated &&
            !isRegistration(request)
        ) {
            try {
                clientVersions.report(authentication.uuid(), version)
            } catch (e: Exception) {
                log.warn("Client version not recorded: {}", SafeDiagnostics.describe(e), e)
            }
        }
        filterChain.doFilter(request, response)
    }

    /** Registration records the build on the device it registers (`DeviceService`). */
    private fun isRegistration(request: HttpServletRequest): Boolean =
        request.method == "POST" && request.requestURI.removePrefix(request.contextPath) == REGISTRATION_PATH

    companion object {
        private val log = LoggerFactory.getLogger(ClientVersionFilter::class.java)
        private const val REGISTRATION_PATH = "/api/device/register-device"

        fun read(request: HttpServletRequest): ClientVersion? = ClientVersion.parse(request.getHeader(ClientVersion.HEADER))
    }
}
