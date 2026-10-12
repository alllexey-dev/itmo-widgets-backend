package dev.alllexey.itmowidgets.backend.platform.http

import jakarta.servlet.http.HttpServletRequest
import java.net.InetAddress

/**
 * A caller's address for rate limiting anonymous routes (web login, pairing). The backend port is reachable only through nginx-hub on the
 * private Docker network, and nginx overwrites `X-Real-IP` with its `$remote_addr`, so that header is trusted
 * only when the direct peer is a private or loopback address (the proxy). `X-Forwarded-For` is never used: its
 * leftmost entries come from the client. `server.forward-headers-strategy` stays unset to keep request.remoteAddr
 * the proxy's own address.
 */
object ClientAddress {
    private val literal = Regex("^[0-9A-Fa-f:.]{2,64}$")

    fun of(request: HttpServletRequest): String {
        val peer = request.remoteAddr.orEmpty()
        val forwarded = request.getHeader("X-Real-IP")?.trim()
        return if (forwarded != null && literal.matches(forwarded) && isProxy(peer)) forwarded else peer.take(64)
    }

    private fun isProxy(peer: String): Boolean {
        if (!literal.matches(peer)) return false
        // A literal address is parsed without a DNS lookup.
        val address = runCatching { InetAddress.getByName(peer) }.getOrNull() ?: return false
        return address.isLoopbackAddress || address.isSiteLocalAddress
    }
}
