package dev.alllexey.itmowidgets.backend.feature.connections.model

import tools.jackson.databind.json.JsonMapper

/**
 * Grant ids (docs/contracts/connections.md#grants): `widgets:<scope>` for every [ConnectionScope] and [IDENTITY], made
 * by Backend, and the ITMO grants the phone makes and seals for the client. Backend never sees an ITMO credential, only
 * these ids. Backend keeps no list of ITMO OIDC clients: any `itmo:refresh:<oidcClient>` is a grant id, and the app
 * decides which it knows.
 */
object Grants {
    const val WIDGETS_PREFIX = "widgets:"
    const val ITMO_PREFIX = "itmo:"
    const val ITMO_REFRESH_PREFIX = "itmo:refresh:"

    /** A Backend-signed JWT with the user's ISU and name, handed to the client once at the claim. */
    const val IDENTITY = "identity"

    /** The refresh token of the client's own ITMO.ID session for MyITMO's OIDC client. */
    const val ITMO_REFRESH = "itmo:refresh:student-personal-cabinet"

    /** That session's `KEYCLOAK_IDENTITY`: every ITMO service; only for clients on the user's own machine. */
    const val ITMO_SSO = "itmo:sso"

    private val OIDC_CLIENT = Regex("^[a-z0-9-]{1,64}$")

    fun widgets(scope: ConnectionScope): String = WIDGETS_PREFIX + scope.wire

    /** The scope of a `widgets:*` id, null for anything else. */
    fun scopeOf(id: String): ConnectionScope? =
        if (id.startsWith(WIDGETS_PREFIX)) ConnectionScope.fromWire(id.removePrefix(WIDGETS_PREFIX)) else null

    fun isItmo(id: String): Boolean = id.startsWith(ITMO_PREFIX)

    /** A Widgets grant: a scope or [IDENTITY]; a connection needs at least one. */
    fun isWidgets(id: String): Boolean = scopeOf(id) != null || id == IDENTITY

    fun isKnown(id: String): Boolean = isWidgets(id) || id == ITMO_SSO ||
        (id.startsWith(ITMO_REFRESH_PREFIX) && OIDC_CLIENT.matches(id.removePrefix(ITMO_REFRESH_PREFIX)))
}

/** One grant a challenge requests; the phone may leave out an optional one. */
data class RequestedGrant(val id: String, val required: Boolean) {
    companion object {
        private val json: JsonMapper = JsonMapper.builder().build()

        /** The stored form, `connection_pair_challenges.requested_grants`: `[{"id": ..., "required": ...}]`. */
        fun format(grants: List<RequestedGrant>): String = json.writeValueAsString(
            grants.map {
                mapOf(
                    "id" to it.id,
                    "required" to it.required,
                )
            },
        )

        fun parse(stored: String): List<RequestedGrant> = json.readTree(stored).iterator().asSequence().map {
            RequestedGrant(it.get("id").stringValue(), it.get("required").booleanValue())
        }.toList()
    }
}
