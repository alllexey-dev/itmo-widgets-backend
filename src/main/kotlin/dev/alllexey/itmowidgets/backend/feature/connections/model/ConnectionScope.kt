package dev.alllexey.itmowidgets.backend.feature.connections.model

import com.fasterxml.jackson.annotation.JsonValue

/**
 * What a connection may do: the routes it may call and, for an agent, the events it receives
 * (docs/contracts/connections.md#scopes). [wire] is the request and storage name. [eventTypes] are FCM `type`s of the
 * push feature, pinned by ConnectionScopeTest.
 */
enum class ConnectionScope(@get:JsonValue val wire: String, val eventTypes: Set<String> = emptySet()) {
    SPORT("sport", setOf("SPORT_FREE_SIGN_LESSONS_PAYLOAD", "SPORT_AUTO_SIGN_LESSONS_PAYLOAD")),
    FRIENDS_READ("friends:read", setOf("FRIENDSHIP_EVENT_PAYLOAD")),
    SCHEDULE_WRITE("schedule:write"),
    REVIEWS_READ("reviews:read"),
    LINKS_READ("links:read"),
    SPORT_QUEUE("sport:queue"),
    USER("user"),
    ;

    companion object {
        fun fromWire(wire: String): ConnectionScope? = entries.firstOrNull { it.wire == wire }

        /** The stored form: wire names in declaration order, comma-separated. */
        fun format(scopes: Set<ConnectionScope>): String = entries.filter { it in scopes }.joinToString(",") { it.wire }

        fun parse(stored: String): Set<ConnectionScope> = stored.split(',').mapNotNull(::fromWire).toSet()
    }
}
