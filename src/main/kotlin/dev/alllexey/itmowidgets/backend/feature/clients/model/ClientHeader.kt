package dev.alllexey.itmowidgets.backend.feature.clients.model

import java.util.UUID

/**
 * What a client says about itself in `X-Client` (ITMO.Widgets ADR 0034): `key=value` pairs separated by `;`, e.g.
 * `kind=android; version=2.3.0; build=20300; channel=play; os=36; install=<uuid>`. It names the build, never the user.
 */
data class ClientHeader(
    val kind: ClientKind,
    val version: String,
    val build: String? = null,
    val channel: String? = null,
    val source: String? = null,
    val commit: String? = null,
    val base: String? = null,
    val os: String? = null,
    val browser: String? = null,
    val install: UUID? = null,
) {
    /**
     * A fork or self-built client: a channel outside the official ones (the build identity says `custom`) or a
     * building repository other than the official ones; `local` alone is no sign, the owner builds locally too.
     * Admin views count it apart, under its upstream [groupVersion].
     */
    val custom: Boolean
        get() = (channel != null && channel !in OFFICIAL_CHANNELS) ||
            (source != null && source != LOCAL_SOURCE && source.lowercase() !in OFFICIAL_SOURCES)

    /** The version a build counts under: an official build's own, a custom build's upstream base when it names one. */
    val groupVersion: String get() = if (custom) base ?: version else version

    companion object {
        const val HEADER = "X-Client"
        const val MAX_HEADER_LENGTH = 512

        val OFFICIAL_CHANNELS = setOf("github", "play", "appstore", "testflight", "sideload", "dev", "web")
        const val LOCAL_SOURCE = "local"
        val OFFICIAL_SOURCES = setOf(
            "alllexey-dev/itmo.widgets",
            "alllexey-dev/itmo-widgets-agent",
            "alllexey-dev/itmo-widgets-web",
            "alllexey-dev/itmo-mcp",
        )

        private val KEY = Regex("^[a-z]+$")
        private val VALUE = Regex("^[A-Za-z0-9._/+-]{1,64}$")
        private val CHANNEL = Regex("^[a-z0-9-]{1,16}$")

        /**
         * The header, or null when it is absent, too long or lacks a known `kind` or a `version`. Invalid pairs and
         * unknown keys are skipped, the first of duplicate keys wins and an invalid `channel` or `install` is dropped.
         */
        fun parse(header: String?): ClientHeader? {
            if (header == null || header.length > MAX_HEADER_LENGTH) return null
            val pairs = LinkedHashMap<String, String>()
            for (part in header.split(';')) {
                val key = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "").trim()
                if (KEY.matches(key) && VALUE.matches(value)) pairs.putIfAbsent(key, value)
            }
            return ClientHeader(
                kind = ClientKind.fromWire(pairs["kind"]) ?: return null,
                version = pairs["version"] ?: return null,
                build = pairs["build"],
                channel = pairs["channel"]?.takeIf(CHANNEL::matches),
                source = pairs["source"],
                commit = pairs["commit"],
                base = pairs["base"],
                os = pairs["os"],
                browser = pairs["browser"],
                install = pairs["install"]?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            )
        }

        private val AGENT_USER_AGENT = Regex("^itmo-widgets-agent/([A-Za-z0-9._+-]{1,64})")

        /** An agent from before `X-Client` names only its version in `User-Agent: itmo-widgets-agent/<version>`. */
        fun fromAgentUserAgent(userAgent: String?): ClientHeader? =
            userAgent?.let(AGENT_USER_AGENT::find)?.let { ClientHeader(ClientKind.AGENT, it.groupValues[1]) }
    }
}
