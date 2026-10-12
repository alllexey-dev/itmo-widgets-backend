package dev.alllexey.itmowidgets.backend.feature.agent.model

import java.time.Instant

/** One stored event; [payload] is its JSON text. */
data class AgentEventRow(
    val seq: Long,
    val dedupKey: String,
    val type: String,
    val payload: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)
