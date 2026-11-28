package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.web.Jwk
import dev.alllexey.itmowidgets.backend.feature.connections.web.Jwks
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * The `identity` grant (docs/contracts/connections.md#identity): a JWT Backend signs with its Ed25519 key for the client
 * at the claim, `sub` the user's ISU, for 5 minutes, checked by the client against `GET /api/pair/jwks`. Without
 * `itmowidgets.connections.identity.private-key` the grant is unavailable and the JWKS empty; a key that is set but is
 * not a base64 PKCS#8 Ed25519 private key stops the start. Tokens and the key are never logged.
 */
@Service
class IdentityTokens(private val config: ConnectionsConfig, private val clock: Clock) {
    private val signingKey: PrivateKey? = config.identity.privateKey.takeIf { it.isNotBlank() }?.let(::parse)
    private val jwks: Jwks = signingKey?.let {
        Jwks(listOf(Jwk(x = EdDsaJws.encode(EdDsaJws.rawPublicKey(EdDsaJws.publicKeyOf(it))), kid = config.identity.keyId)))
    } ?: Jwks(emptyList())

    init {
        if (signingKey == null) {
            log.info("Connection identity is off: no signing key")
        } else {
            log.info("Connection identity signs with key {}", config.identity.keyId)
        }
    }

    val available: Boolean get() = signingKey != null

    fun jwks(): Jwks = jwks

    /**
     * The identity of the user with [isu] and [name] (left out when null) for [clientId], the audience, over the
     * connection [connectionId]. Only while [available].
     */
    fun issue(clientId: String, isu: Int, name: String?, connectionId: UUID, jti: UUID = UUID.randomUUID()): String {
        val key = checkNotNull(signingKey) { "Connection identity is off" }
        val issuedAt = clock.instant().epochSecond
        val claims = linkedMapOf<String, Any>(
            "iss" to config.audience,
            "aud" to clientId,
            "sub" to isu.toString(),
            "isu" to isu,
        )
        name?.let { claims["name"] = it }
        claims["cid"] = connectionId.toString()
        claims["jti"] = jti.toString()
        claims["iat"] = issuedAt
        claims["exp"] = issuedAt + LIFETIME.seconds
        return EdDsaJws.sign(linkedMapOf("alg" to EdDsaJws.ALG, "kid" to config.identity.keyId, "typ" to "JWT"), claims, key)
    }

    private fun parse(value: String): PrivateKey {
        val body = value.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").filterNot(Char::isWhitespace)
        return try {
            EdDsaJws.privateKey(Base64.getDecoder().decode(body))
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException(INVALID_KEY)
        } catch (_: GeneralSecurityException) {
            throw IllegalStateException(INVALID_KEY)
        }
    }

    companion object {
        val LIFETIME: Duration = Duration.ofMinutes(5)
        private const val INVALID_KEY = "itmowidgets.connections.identity.private-key is not base64 of a PKCS#8 Ed25519 private key"
        private val log = LoggerFactory.getLogger(IdentityTokens::class.java)
    }
}
