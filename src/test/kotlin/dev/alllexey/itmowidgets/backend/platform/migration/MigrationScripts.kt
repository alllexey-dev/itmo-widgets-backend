package dev.alllexey.itmowidgets.backend.platform.migration

import org.flywaydb.core.api.MigrationVersion
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * The versioned Flyway scripts on the classpath, as the application finds them. Tests derive the
 * current version and every "scripts still to run" count from here, so a new `V<n>__*.sql` needs no
 * test edit. A check that is only true up to some version migrates to that version explicitly.
 */
object MigrationScripts {
    /** Each script's version by its description, the file name after `__` without `.sql`. */
    private val byDescription: Map<String, MigrationVersion> by lazy {
        PathMatchingResourcePatternResolver()
            .getResources("classpath*:db/migration/V*__*.sql")
            .map { requireNotNull(it.filename) }
            .associate { name ->
                name.substringAfter("__").removeSuffix(".sql") to MigrationVersion.fromVersion(name.removePrefix("V").substringBefore("__"))
            }
            .also { check(it.isNotEmpty()) { "no Flyway scripts in classpath:db/migration" } }
    }

    val versions: List<MigrationVersion> by lazy { byDescription.values.sorted() }

    val latest: String get() = versions.last().version

    val count: Int get() = versions.size

    /** The version of `V<n>__<description>.sql`, so a script's test survives the integrator's renumbering. */
    fun versionOf(description: String): String =
        requireNotNull(byDescription[description]) { "no Flyway script V<n>__$description.sql" }.version

    /** The version that runs right before [version]: the schema that script finds. */
    fun before(version: String): String = MigrationVersion.fromVersion(version).let { next -> versions.last { it < next }.version }

    /** How many scripts a schema migrated to [version] still runs to reach [latest]. */
    fun countAfter(version: String): Int = MigrationVersion.fromVersion(version).let { from -> versions.count { it > from } }
}
