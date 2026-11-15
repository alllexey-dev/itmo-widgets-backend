package dev.alllexey.itmowidgets.backend.feature.connections.model

import dev.alllexey.itmowidgets.backend.platform.metrics.DailyMetric

/**
 * The steps of a `CONNECT` pairing counted per day (docs/contracts/server-statistics.md): [METRIC] with the step as
 * dim1 and the [ClientType] name as dim2. Renewals are not counted.
 */
enum class PairingStep {
    CREATED,
    OPENED,
    APPROVED,
    CLAIMED,
    EXPIRED,
    ;

    companion object {
        val METRIC = DailyMetric("pairing")
    }
}
