package dev.alllexey.itmowidgets.backend.feature.clients.model

/** What kind of client a [ClientInstanceEntity] is; `kind` in `X-Client` is the lowercase name. */
enum class ClientKind {
    ANDROID,
    IOS,
    WEB,
    AGENT,
    ;

    companion object {
        fun fromWire(value: String?): ClientKind? = entries.firstOrNull { it.name.lowercase() == value }
    }
}
