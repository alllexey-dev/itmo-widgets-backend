package dev.alllexey.itmowidgets.backend.platform.security

import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * The ITMO.ID clients (`azp`) whose access tokens Backend accepts. `log` accepts every client and only counts it;
 * `enforce` rejects a client outside `id.itmo.allowed-clients`. The hourly line carries client ids and counts,
 * never a token or a user.
 */
@Component
class ItmoClientPolicy(@Value($$"${id.itmo.allowed-clients}") allowedClients: String, @Value($$"${id.itmo.azp-mode}") mode: String) {
    private val allowed = allowedClients.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
    private val enforce = when (mode.trim().lowercase()) {
        "log" -> false
        "enforce" -> true
        else -> throw IllegalArgumentException("id.itmo.azp-mode must be log or enforce, was '$mode'")
    }
    private val counts = ConcurrentHashMap<String, LongAdder>()

    /** Counts the verified token's client; in `enforce` mode a client outside the allowlist fails verification. */
    fun check(token: DecodedJWT) {
        val client = token.getClaim(AZP).asString()
        counter(label(client)).increment()
        if (enforce && client !in allowed) throw JWTVerificationException("The ITMO.ID client is not allowed")
    }

    /** The counts since the previous call by client, sorted by client; the counters restart from zero. */
    fun drainCounts(): Map<String, Long> = counts.entries
        .mapNotNull { (client, count) -> count.sumThenReset().takeIf { it > 0 }?.let { client to it } }
        .sortedBy { it.first }
        .toMap()

    @Scheduled(cron = "0 0 * * * *", zone = "Europe/Moscow")
    fun logCounts() {
        val drained = drainCounts()
        if (drained.isNotEmpty()) {
            log.info("azp counts: {}", drained.entries.joinToString(", ", "{", "}") { "${it.key}: ${it.value}" })
        }
    }

    // Verified tokens come from ITMO.ID, so the set of clients is small; the cap keeps the map bounded anyway.
    private fun counter(label: String): LongAdder = counts[label]
        ?: if (counts.size < MAX_CLIENTS) counts.computeIfAbsent(label) { LongAdder() } else counts.computeIfAbsent(OTHER) { LongAdder() }

    private fun label(client: String?): String = when {
        client.isNullOrBlank() -> NONE
        else -> client.take(MAX_LABEL_LENGTH)
    }

    companion object {
        private const val AZP = "azp"
        const val NONE = "(none)"
        const val OTHER = "(other)"
        private const val MAX_CLIENTS = 32
        private const val MAX_LABEL_LENGTH = 64
        private val log: Logger = LoggerFactory.getLogger(ItmoClientPolicy::class.java)
    }
}
