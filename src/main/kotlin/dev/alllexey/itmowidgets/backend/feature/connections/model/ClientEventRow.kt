package dev.alllexey.itmowidgets.backend.feature.connections.model

import java.time.Instant

/** One stored event; [payload] is its JSON text. */
data class ClientEventRow(
    val seq: Long,
    val dedupKey: String,
    val type: String,
    val payload: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)
