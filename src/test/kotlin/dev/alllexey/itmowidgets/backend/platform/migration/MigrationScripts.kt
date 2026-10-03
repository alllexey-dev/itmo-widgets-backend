package dev.alllexey.itmowidgets.backend.platform.migration

import org.flywaydb.core.api.MigrationVersion
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * The versioned Flyway scripts on the classpath, as the application finds them. Tests derive the
 * current version and every "scripts still to run" count from here, so a new `V<n>__*.sql` needs no
 * test edit. A check that is only true up to some version migrates to that version explicitly.
 */
object MigrationScripts {
    val versions: List<MigrationVersion> by lazy {
        PathMatchingResourcePatternResolver()
            .getResources("classpath*:db/migration/V*__*.sql")
            .map { MigrationVersion.fromVersion(requireNotNull(it.filename).removePrefix("V").substringBefore("__")) }
            .sorted()
            .also { check(it.isNotEmpty()) { "no Flyway scripts in classpath:db/migration" } }
    }

    val latest: String get() = versions.last().version

    val count: Int get() = versions.size

    /** How many scripts a schema migrated to [version] still runs to reach [latest]. */
    fun countAfter(version: String): Int = MigrationVersion.fromVersion(version).let { from -> versions.count { it > from } }
}
