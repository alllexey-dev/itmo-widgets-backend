package dev.alllexey.itmowidgets.backend.feature.clients.model

/** What kind of client a [ClientInstanceEntity] is; `kind` in `X-Client` is the lowercase name. */
enum class ClientKind {
    ANDROID,
    IOS,
    WEB,
    AGENT,
    MCP,
    ;

    /** An app install, keyed by its own install id; the other kinds are connections, keyed by the connection id. */
    val isApp: Boolean get() = this == ANDROID || this == IOS

    companion object {
        fun fromWire(value: String?): ClientKind? = entries.firstOrNull { it.name.lowercase() == value }
    }
}
