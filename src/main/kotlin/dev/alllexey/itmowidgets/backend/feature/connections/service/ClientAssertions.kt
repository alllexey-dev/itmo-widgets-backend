package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientAuth
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionClient
import dev.alllexey.itmowidgets.backend.feature.connections.persistence.ConnectionClientRepository
import dev.alllexey.itmowidgets.backend.platform.error.InvalidClientException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Client assertions (docs/contracts/connections.md#confidential-clients): a confidential client signs every create,
 * renewal and claim with a key of its registry entry, a public client sends none. An assertion is an EdDSA JWS of at
 * most a minute, for this Backend ([ConnectionsConfig.audience]), used once: its `jti` is recorded until it expires.
 * A rejection never says which check failed; the log names it, never the token.
 */
@Service
class ClientAssertions(
    private val repository: ConnectionClientRepository,
    private val config: ConnectionsConfig,
    private val clock: Clock,
) {
    /**
     * 400 when a public [client] sends an assertion, 401 `invalid_client` when a confidential one sends none or one that
     * does not hold. A valid assertion is used up in the caller's transaction.
     */
    fun require(client: ConnectionClient, assertion: String?) {
        when (client.auth) {
            ClientAuth.PUBLIC -> if (assertion != null) throw InvalidRequestDataException("A public client sends no clientAssertion")

            ClientAuth.CONFIDENTIAL -> {
                val problem = if (assertion == null) "missing" else problem(client, assertion, clock.instant())
                if (problem != null) {
                    log.info("Client assertion of {} rejected: {}", client.id, problem)
                    throw InvalidClientException("Client authentication failed")
                }
            }
        }
    }

    /** Assertion ids past their life go with the pairing expiry job. */
    fun deleteExpired(now: Instant): Int = repository.deleteAssertionsExpiredBefore(now)

    /** Why [assertion] does not prove [client], or null when it does and its id was free (now used). */
    private fun problem(client: ConnectionClient, assertion: String, now: Instant): String? {
        val token = EdDsaJws.parse(assertion, MAX_LENGTH) ?: return "malformed"
        if (token.header.text("alg") != EdDsaJws.ALG) return "alg"
        val kid = token.header.text("kid") ?: return "no kid"
        val key = client.keys.firstOrNull { it.kid == kid }?.let { EdDsaJws.decode(it.publicKey) }?.let(EdDsaJws::publicKey)
            ?: return "unknown kid"
        if (!token.verifiedBy(key)) return "signature"
        val claims = token.claims
        if (claims.text("iss") != client.id || claims.text("sub") != client.id) return "iss or sub"
        if (!audienceOf(claims.get("aud"))) return "aud"
        val jti = claims.text("jti")?.takeIf { it.length in 1..MAX_JTI } ?: return "jti"
        val issuedAt = claims.seconds("iat") ?: return "iat"
        val expires = claims.seconds("exp") ?: return "exp"
        if (expires <= issuedAt || expires - issuedAt > MAX_LIFETIME.seconds) return "lifetime"
        val nowSeconds = now.epochSecond
        if (nowSeconds < issuedAt - SKEW.seconds || nowSeconds > expires + SKEW.seconds) return "expired or not yet valid"
        if (!repository.useAssertion(client.id, jti, Instant.ofEpochSecond(expires).plus(SKEW))) return "replayed jti"
        return null
    }

    private fun audienceOf(aud: JsonNode?): Boolean = when {
        aud == null -> false
        aud.isString -> aud.stringValue() == config.audience
        aud.isArray -> aud.iterator().asSequence().any { it.isString && it.stringValue() == config.audience }
        else -> false
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.stringValue()

    private fun JsonNode.seconds(field: String): Long? = get(field)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()

    companion object {
        /** An assertion lives at most this long, `exp - iat`. */
        val MAX_LIFETIME: Duration = Duration.ofSeconds(60)

        /** Clock difference allowed between the client and Backend on both ends. */
        val SKEW: Duration = Duration.ofSeconds(30)
        const val MAX_JTI = 128

        /** Far more than a header, the claims and a signature need. */
        const val MAX_LENGTH = 4096
        private val log = LoggerFactory.getLogger(ClientAssertions::class.java)
    }
}
