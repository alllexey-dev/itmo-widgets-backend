package dev.alllexey.itmowidgets.backend.feature.connections.model

import com.fasterxml.jackson.annotation.JsonValue
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope.FRIENDS_READ
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope.LINKS_READ
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope.REVIEWS_READ
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope.SCHEDULE_WRITE
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope.SPORT
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope.SPORT_QUEUE
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope.USER

/**
 * The kind of a connected client and its scopes: [defaults] when a pairing asks for none, never more than [allowed]
 * (docs/contracts/connections.md#clients). Only an agent and an MCP server get an ITMO.ID session of their own.
 */
enum class ClientType(@get:JsonValue val wire: String, val allowed: Set<ConnectionScope>, val defaults: Set<ConnectionScope>) {
    AGENT("agent", setOf(SPORT, FRIENDS_READ, SCHEDULE_WRITE), setOf(SPORT)),
    MCP("mcp", setOf(REVIEWS_READ, LINKS_READ, SPORT_QUEUE), setOf(REVIEWS_READ, LINKS_READ)),
    WEB("web", setOf(USER), setOf(USER)),
    ;

    val hasItmoSession: Boolean get() = this != WEB

    companion object {
        fun fromWire(wire: String): ClientType? = entries.firstOrNull { it.wire == wire }
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
