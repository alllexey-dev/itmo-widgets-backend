package dev.alllexey.itmowidgets.backend.feature.connections.model

import java.time.LocalDate

/** The connection figures of one statistics period; [days] has one row per day of the period. */
data class ConnectionStats(val days: List<ConnectionStatsDay>)

/**
 * One Europe/Moscow day: connections created and revoked on it per client, every [ClientType] a key. A revoked
 * connection is deleted 90 days after its revocation (`ConnectionRetention.REVOKED_RETENTION`), so older days undercount.
 */
data class ConnectionStatsDay(val day: LocalDate, val created: Map<ClientType, Long>, val revoked: Map<ClientType, Long>)

/** Connections of one client created and revoked on [day]; a row the statistics repository returns. */
data class ConnectionDayCount(val client: ClientType, val day: LocalDate, val created: Long, val revoked: Long)

/**
 * The `CONNECT` pairings of one [client] over a statistics period, counted step by step ([PairingStep.METRIC]); every
 * [PairingStep] is a key of [steps].
 */
data class PairingFunnel(val client: ClientType, val steps: Map<PairingStep, Long>)
