package dev.alllexey.itmowidgets.backend.feature.agent.model

import com.fasterxml.jackson.annotation.JsonValue

/**
 * What an agent token may do: the events it receives and the routes it may call (docs/contracts/agent.md).
 * [wire] is the request and storage name. [eventTypes] are FCM `type`s of the push feature, pinned by AgentScopeTest.
 */
enum class AgentScope(@get:JsonValue val wire: String, val eventTypes: Set<String>) {
    SPORT("sport", setOf("SPORT_FREE_SIGN_LESSONS_PAYLOAD", "SPORT_AUTO_SIGN_LESSONS_PAYLOAD")),
    FRIENDS_READ("friends:read", setOf("FRIENDSHIP_EVENT_PAYLOAD")),
    SCHEDULE_WRITE("schedule:write", emptySet()),
    ;

    companion object {
        fun fromWire(wire: String): AgentScope? = entries.firstOrNull { it.wire == wire }

        /** The stored form: wire names in declaration order, comma-separated. */
        fun format(scopes: Set<AgentScope>): String = entries.filter { it in scopes }.joinToString(",") { it.wire }

        fun parse(stored: String): Set<AgentScope> = stored.split(',').mapNotNull(::fromWire).toSet()
    }
}

/** How a token came to be: minted at a pairing claim, or created by hand in the app or on the web. */
enum class AgentTokenOrigin { PAIR, APP, WEB }
