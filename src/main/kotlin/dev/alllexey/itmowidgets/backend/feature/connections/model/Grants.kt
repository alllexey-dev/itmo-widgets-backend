package dev.alllexey.itmowidgets.backend.feature.connections.model

import tools.jackson.databind.json.JsonMapper

/**
 * Grant ids (docs/contracts/connections.md#grants): `widgets:<scope>` for every [ConnectionScope], made by Backend, and
 * the ITMO grants the phone makes and seals for the client. Backend never sees an ITMO credential, only these ids.
 */
object Grants {
    const val WIDGETS_PREFIX = "widgets:"
    const val ITMO_PREFIX = "itmo:"

    /** The refresh token of the client's own ITMO.ID session for MyITMO's OIDC client. */
    const val ITMO_REFRESH = "itmo:refresh:student-personal-cabinet"

    /** That session's `KEYCLOAK_IDENTITY`: every ITMO service; only for clients on the user's own machine. */
    const val ITMO_SSO = "itmo:sso"

    /** The ITMO grants this Backend knows; any other `itmo:*` id is unknown. */
    val ITMO: List<String> = listOf(ITMO_REFRESH, ITMO_SSO)

    fun widgets(scope: ConnectionScope): String = WIDGETS_PREFIX + scope.wire

    /** The scope of a `widgets:*` id, null for anything else. */
    fun scopeOf(id: String): ConnectionScope? =
        if (id.startsWith(WIDGETS_PREFIX)) ConnectionScope.fromWire(id.removePrefix(WIDGETS_PREFIX)) else null

    fun isItmo(id: String): Boolean = id.startsWith(ITMO_PREFIX)

    fun isKnown(id: String): Boolean = scopeOf(id) != null || id in ITMO
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
