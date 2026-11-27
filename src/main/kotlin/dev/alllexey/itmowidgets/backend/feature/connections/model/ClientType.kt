package dev.alllexey.itmowidgets.backend.feature.connections.model

import com.fasterxml.jackson.annotation.JsonValue

/**
 * The kind of a connected client (docs/contracts/connections.md#clients): the beta switches and the client routes go by
 * kind, what a client may request by its entry in the registry ([ConnectionClient]). [legacyClientId] is the seeded
 * registry entry a request naming only the kind (`client`, protocol 1) pairs as; `app` is for client apps registered
 * later and has none.
 */
enum class ClientType(@get:JsonValue val wire: String, val legacyClientId: String?) {
    AGENT("agent", "itmo-widgets-agent"),
    MCP("mcp", "itmo-mcp"),
    WEB("web", "web"),
    APP("app", null),
    ;

    companion object {
        fun fromWire(wire: String): ClientType? = entries.firstOrNull { it.wire == wire }

        /** The kind whose seeded entry [clientId] is, if any. */
        fun ofLegacyClientId(clientId: String): ClientType? = entries.firstOrNull { it.legacyClientId == clientId }
    }
}

/** The client's own report of its ITMO.ID session: working, closed by ITMO (it waits for a renewal) or none. */
enum class ItmoStatus(@get:JsonValue val wire: String) {
    OK("ok"),
    CLOSED("closed"),
    NONE("none"),
    ;

    companion object {
        fun fromWire(wire: String): ItmoStatus? = entries.firstOrNull { it.wire == wire }
    }
}
