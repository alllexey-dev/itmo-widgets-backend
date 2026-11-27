package dev.alllexey.itmowidgets.backend.feature.connections.model

import com.fasterxml.jackson.annotation.JsonValue
import java.time.Instant

/** Where a client app runs: on the user's own machine for that user alone, or as a service holding many users' data. */
enum class ClientDeployment(@get:JsonValue val wire: String) {
    SELF_HOSTED("self_hosted"),
    HOSTED("hosted"),
    ;

    companion object {
        fun fromWire(wire: String): ClientDeployment? = entries.firstOrNull { it.wire == wire }
    }
}

/** How a client app proves itself: `public` cannot, `confidential` will sign with its key (not accepted at pairing yet). */
enum class ClientAuth(@get:JsonValue val wire: String) {
    PUBLIC("public"),
    CONFIDENTIAL("confidential"),
    ;

    companion object {
        fun fromWire(wire: String): ClientAuth? = entries.firstOrNull { it.wire == wire }
    }
}

enum class ClientStatus(@get:JsonValue val wire: String) {
    ACTIVE("active"),
    SUSPENDED("suspended"),
    ;

    companion object {
        fun fromWire(wire: String): ClientStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/** A grant a registered client may request: [required] for it to work, on by default, and what the client does with it. */
data class ClientGrant(val id: String, val required: Boolean, val requestedByDefault: Boolean, val purpose: String?)

/**
 * One registered client app (docs/contracts/connections.md#registry): `connection_clients` with its grants in their
 * order. [maxPerUser] null means `itmowidgets.connections.max-per-client`.
 */
data class ConnectionClient(
    val id: String,
    val kind: ClientType,
    val name: String,
    val homepage: String?,
    val iconUrl: String?,
    val deployment: ClientDeployment,
    val auth: ClientAuth,
    val status: ClientStatus,
    val maxPerUser: Int?,
    val grants: List<ClientGrant>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** The Widgets scopes it may hold, in registry order. */
    val scopes: List<ConnectionScope> get() = grants.mapNotNull { Grants.scopeOf(it.id) }

    fun grant(id: String): ClientGrant? = grants.firstOrNull { it.id == id }

    /**
     * The policy every registry write and every pairing checks: the ITMO.ID session cookie only for a client on the
     * user's own machine, and the web never gets an ITMO grant. Null when [grantIds] keep it, else the broken rule.
     */
    fun policyViolation(grantIds: Collection<String>): String? = when {
        Grants.ITMO_SSO in grantIds && deployment != ClientDeployment.SELF_HOSTED -> "itmo:sso is only for self_hosted clients"
        kind == ClientType.WEB && grantIds.any(Grants::isItmo) -> "A web client has no itmo grants"
        else -> null
    }
}
