package dev.alllexey.itmowidgets.backend.platform.metrics

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.util.ServletRequestPathUtils
import java.time.Duration

/**
 * Counts every `/api/` answer in [HttpCounters.HTTP] and times it into [HttpCounters.HTTP_MS], both by route group.
 * Registered first of all filters by `HttpCounterFilterConfig`, around the body limit and Spring Security, so their
 * 413, 401 and 403 answers are counted too.
 *
 * The route group is the first segment after `/api/` of the route pattern that matched (`/api/users/{isu}` is `users`),
 * never of the raw path, so no ISU or id in a path is stored. A request the security chain answered never reached a
 * handler; its route is looked up the way the dispatcher would have. No match, or a pattern without a literal first
 * segment, is [HttpCounters.OTHER].
 *
 * An asynchronous request (the agent long-poll is the only one) is counted at the asynchronous dispatch that writes
 * its answer, with that status, and is not timed: it waits by design. A request that fails out of the chain counts as
 * a 5xx, the answer the container then gives.
 */
class HttpCounterFilter(counters: DailyCounters, private val mappings: ObjectProvider<RequestMappingHandlerMapping>) :
    OncePerRequestFilter() {
    private val requests = counters.counter(HttpCounters.HTTP)
    private val latency = counters.counter(HttpCounters.HTTP_MS)

    /** The asynchronous dispatch writes the answer of an asynchronous request: it is counted there. */
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val started = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } catch (error: Throwable) {
            if (!isAsyncStarted(request)) record(request, FAILED, started)
            throw error
        }
        if (isAsyncStarted(request)) {
            // The route is matched now; the answer comes with a later asynchronous dispatch.
            if (!isAsyncDispatch(request)) request.setAttribute(GROUP_ATTRIBUTE, routeGroup(request))
            return
        }
        record(request, response.status, started)
    }

    private fun record(request: HttpServletRequest, status: Int, started: Long) {
        val group = request.getAttribute(GROUP_ATTRIBUTE) as? String ?: routeGroup(request)
        HttpCounters.statusClass(status)?.let { requests.increment(group, it) }
        if (!isAsyncDispatch(request)) latency.increment(group, LatencyBucket.of(Duration.ofNanos(System.nanoTime() - started)).label)
    }

    private fun routeGroup(request: HttpServletRequest): String =
        (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) ?: lookUp(request))?.toString()?.let(::groupOf)
            ?: HttpCounters.OTHER

    /** The pattern the dispatcher would have matched; null when none or when matching fails (405, 406). */
    private fun lookUp(request: HttpServletRequest): Any? = try {
        if (!ServletRequestPathUtils.hasParsedRequestPath(request)) ServletRequestPathUtils.parseAndCache(request)
        mappings.getObject().getHandler(request)?.let { request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val FAILED = 500
        private val GROUP_ATTRIBUTE = HttpCounterFilter::class.java.name + ".group"
        private const val PREFIX = "/api/"
        private val SEGMENT = Regex("^[a-z0-9-]{1,64}$")

        /** The group of a route pattern: its literal first segment after `/api/`, or null. */
        fun groupOf(pattern: String): String? =
            pattern.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.substringBefore('/')?.takeIf(SEGMENT::matches)
    }
}
