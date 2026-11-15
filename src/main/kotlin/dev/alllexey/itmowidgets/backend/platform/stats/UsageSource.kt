package dev.alllexey.itmowidgets.backend.platform.stats

import java.util.UUID

/**
 * How much one feature was used in a period: [events] the feature counts (null for a feature without a countable
 * event) and the distinct [users] behind them.
 */
data class FeatureUsage(val events: Long?, val users: Set<UUID>)

/**
 * One row of the feature-use statistics, owned by the feature it measures. A feature registers one bean per row; the
 * admin statistics collect every bean, so a new row is one new bean and no admin change. The users of every row that
 * is an [ownAction] are united into the people who used any feature, which is why a source returns ids instead of a
 * count. A source returns the ids its rows name as they are; the caller keeps only those of accounts, in one place for
 * every source.
 *
 * A source reads only its own feature's tables, runs a fixed number of aggregate queries and loads no entities; the
 * caller holds the read-only transaction.
 */
interface UsageSource {
    /** The row's name on the wire, upper snake case and stable once released, e.g. `SPORT_AUTO_SIGN`. */
    val feature: String

    /**
     * Whether the row counts what its users did themselves. A row of something that happens to them without an action
     * of theirs, such as the schedule the app uploads in the background, is false: it is listed with its own users,
     * but they do not count as people who used a feature.
     */
    val ownAction: Boolean get() = true

    fun usage(period: StatsPeriod): FeatureUsage
}
