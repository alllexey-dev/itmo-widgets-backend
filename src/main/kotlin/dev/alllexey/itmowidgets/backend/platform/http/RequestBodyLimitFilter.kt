package dev.alllexey.itmowidgets.backend.platform.http

import dev.alllexey.itmowidgets.backend.platform.error.ErrorCode
import dev.alllexey.itmowidgets.backend.platform.security.writeApiError
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * Caps every request body at [MAX_BYTES], before authentication. A larger declared length answers 413
 * `invalid_request` unread; a body without a length (chunked) fails reading past the cap, which a JSON route
 * answers as 400 `invalid_request`. The edge proxy caps the same; this keeps the JVM safe without it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestBodyLimitFilter(private val jsonMapper: JsonMapper) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val length = request.contentLengthLong
        if (length > MAX_BYTES) {
            response.writeApiError(jsonMapper, HttpStatus.CONTENT_TOO_LARGE, "Request body too large", ErrorCode.INVALID_REQUEST)
            return
        }
        filterChain.doFilter(if (length < 0) LimitedBodyRequest(request) else request, response)
    }

    private class LimitedBodyRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
        private val body by lazy { LimitedInputStream(request.inputStream) }

        override fun getInputStream(): ServletInputStream = body

        override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(body, characterEncoding ?: Charsets.UTF_8.name()))
    }

    private class LimitedInputStream(private val body: ServletInputStream) : ServletInputStream() {
        private var read = 0L

        override fun read(): Int = body.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = body.read(b, off, len).also { if (it > 0) count(it.toLong()) }

        override fun isFinished(): Boolean = body.isFinished

        override fun isReady(): Boolean = body.isReady

        override fun setReadListener(listener: ReadListener) = body.setReadListener(listener)

        private fun count(bytes: Long) {
            read += bytes
            if (read > MAX_BYTES) throw IOException("Request body exceeds $MAX_BYTES bytes")
        }
    }

    companion object {
        /** The largest real body, a schedule sync, stays under 1 MB: production holds at most 718 lessons a user (2026-10). */
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
