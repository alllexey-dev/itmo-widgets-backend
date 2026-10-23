package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope

/**
 * The routes a connection may call (docs/contracts/connections.md#scopes). A listed route needs its scope and client
 * type; `user` (the web) reaches every other route except the client routes and the phone-only ones. Everything else
 * is denied before a controller runs.
 */
object ConnectionRoutePolicy {
    /**
     * [scope] null: any connection of [clients]; [events]: the long-poll, with its own rate limit; [selfRevoke]: the
     * connection ending itself, which works with its client type off too, since it only takes access away.
     */
    data class Route(
        val method: String,
        val path: Regex,
        val scope: ConnectionScope?,
        val clients: Set<ClientType> = ClientType.entries.toSet(),
        val events: Boolean = false,
        val selfRevoke: Boolean = false,
    )

    private const val ID = "[0-9]+"
    private const val ANY = "[^/]+"
    private val ITMO_CLIENTS = setOf(ClientType.AGENT, ClientType.MCP)

    val routes: List<Route> = listOf(
        Route("GET", Regex("/api/client/me"), null),
        Route("DELETE", Regex("/api/client/self"), null, selfRevoke = true),
        Route("POST", Regex("/api/client/pair/renew"), null),
        Route("PUT", Regex("/api/client/status"), null, ITMO_CLIENTS),
        Route("GET", Regex("/api/client/events"), null, setOf(ClientType.AGENT), events = true),
        Route("GET", Regex("/api/app/.+"), null),
        Route("GET", Regex("/api/sport/(auto|free)-sign/entry/my"), ConnectionScope.SPORT),
        Route("POST", Regex("/api/sport/(auto|free)-sign/lesson/$ID/(mark-satisfied|cancel)"), ConnectionScope.SPORT),
        Route("POST", Regex("/api/sport/sign/sync"), ConnectionScope.SPORT),
        Route("GET", Regex("/api/friends"), ConnectionScope.FRIENDS_READ),
        Route("POST", Regex("/api/schedule/lessons/sync"), ConnectionScope.SCHEDULE_WRITE),
        Route("GET", Regex("/api/teachers/$ID/reviews"), ConnectionScope.REVIEWS_READ),
        Route("GET", Regex("/api/teachers/summary-levels"), ConnectionScope.REVIEWS_READ),
        Route("GET", Regex("/api/subjects/$ANY/links"), ConnectionScope.LINKS_READ),
        Route("GET", Regex("/api/sport/auto-sign/limits"), ConnectionScope.SPORT_QUEUE),
        Route("POST", Regex("/api/sport/(auto|free)-sign/entry/create"), ConnectionScope.SPORT_QUEUE),
        Route("GET", Regex("/api/sport/(auto|free)-sign/entry/my"), ConnectionScope.SPORT_QUEUE),
        Route("POST", Regex("/api/sport/(auto|free)-sign/entry/$ID/cancel"), ConnectionScope.SPORT_QUEUE),
    )

    /** Only the app's ITMO.ID bearer: approving clients, consent, the app's own identity and push registration. */
    private val phoneOnly: List<Pair<String?, Regex>> = listOf(
        null to Regex("/api/users/me/pair/.*"),
        "PUT" to Regex("/api/users/me/consent"),
        "PUT" to Regex("/api/users/me/id-token"),
        null to Regex("/api/device/.*"),
        "POST" to Regex("/api/users/me/connections/$ANY/itmo-ended"),
    )

    private val userRoute = Route("*", Regex(".*"), ConnectionScope.USER)

    /** The route a connection of [client] with [scopes] may call, or null when it is denied. */
    fun permitted(client: ClientType, scopes: Set<ConnectionScope>, method: String, path: String): Route? = routes.firstOrNull {
        it.method == method && it.path.matches(path) && client in it.clients &&
            (it.scope == null || it.scope in scopes)
    }
        ?: userRoute.takeIf {
            ConnectionScope.USER in scopes && !path.startsWith("/api/client/") &&
                phoneOnly.none { (verb, pattern) -> (verb == null || verb == method) && pattern.matches(path) }
        }
}
