package dev.alllexey.itmowidgets.backend.feature.connections.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Connected clients (docs/contracts/connections.md). [agentEnabled] and [mcpEnabled] switch a client type off for
 * everyone; agents also need an admin's per-user grant. An agent is live while it polled within [liveness]. A user has
 * at most [maxPerClient] live connections of each type. A web connection ends after [webIdle] without requests, and
 * reaches admin and moderation routes only within [adminMaxAge] after its last phone approval. [audience] is Backend's
 * name in client assertions (their `aud`) and identity tokens (their `iss`); [identity] the key it signs identity tokens
 * with, none by default: without it the `identity` grant is unavailable.
 */
@ConfigurationProperties("itmowidgets.connections")
data class ConnectionsConfig(
    val agentEnabled: Boolean = true,
    val mcpEnabled: Boolean = true,
    val liveness: Duration = Duration.ofMinutes(3),
    val maxPerClient: Int = 5,
    val webIdle: Duration = Duration.ofDays(30),
    val adminMaxAge: Duration = Duration.ofDays(7),
    val audience: String = "https://widgets.alllexey.dev",
    val identity: Identity = Identity(),
) {
    /** [privateKey]: base64 of a PKCS#8 Ed25519 private key, empty for none; [keyId] its `kid` in the JWKS. */
    data class Identity(val privateKey: String = "", val keyId: String = "identity-1") {
        override fun toString(): String = "Identity(privateKey=${if (privateKey.isBlank()) "none" else "set"}, keyId=$keyId)"
    }

    init {
        require(liveness > Duration.ofMinutes(1)) { "Connection liveness must be longer than the one-minute poll step" }
        require(maxPerClient > 0) { "Connections max-per-client must be positive" }
        require(webIdle > Duration.ZERO) { "Connections web-idle must be positive" }
        require(adminMaxAge > Duration.ZERO) { "Connections admin-max-age must be positive" }
        require(audience.isNotBlank()) { "Connections audience must be set" }
        require(KEY_ID.matches(identity.keyId)) { "Connections identity key-id must be 1-64 characters of A-Z, a-z, 0-9, ., _ and -" }
    }
}

private val KEY_ID = Regex("^[A-Za-z0-9._-]{1,64}$")
