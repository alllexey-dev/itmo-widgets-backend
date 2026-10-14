package dev.alllexey.itmowidgets.backend.feature.agent.service

import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentScope

/**
 * The only routes an agent token may call (docs/contracts/agent.md#scopes). Everything else, including token, connection,
 * privacy, account, web login, pairing approval, admin and moderation routes, is denied before a controller runs.
 */
object AgentRoutePolicy {
    /**
     * [scope] null: any token; [events]: the long-poll, with its own rate limit; [selfRevoke]: the token ending itself, which
     * works with access off too, since it only takes access away.
     */
    data class AgentRoute(
        val method: String,
        val path: Regex,
        val scope: AgentScope?,
        val events: Boolean = false,
        val selfRevoke: Boolean = false,
    )

    private const val ID = "[0-9]+"

    val routes: List<AgentRoute> = listOf(
        AgentRoute("GET", Regex("/api/agent/me"), null),
        AgentRoute("GET", Regex("/api/agent/events"), null, events = true),
        AgentRoute("DELETE", Regex("/api/agent/self"), null, selfRevoke = true),
        AgentRoute("POST", Regex("/api/agent/pair/session"), null),
        AgentRoute("GET", Regex("/api/sport/(auto|free)-sign/entry/my"), AgentScope.SPORT),
        AgentRoute("POST", Regex("/api/sport/(auto|free)-sign/lesson/$ID/(mark-satisfied|cancel)"), AgentScope.SPORT),
        AgentRoute("POST", Regex("/api/sport/sign/sync"), AgentScope.SPORT),
        AgentRoute("POST", Regex("/api/schedule/lessons/sync"), AgentScope.SCHEDULE_WRITE),
    )

    /** The route a token with [scopes] (wire names) may call, or null when it is denied. */
    fun permitted(scopes: Set<String>, method: String, path: String): AgentRoute? =
        routes.firstOrNull { it.method == method && it.path.matches(path) }?.takeIf { it.scope == null || it.scope.wire in scopes }
}
