package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V31__stat_daily.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V31StatDailyTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("stat_daily")

    @Test
    fun `counters are unique per day, metric and labels, never negative and outlive the account`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)
        val user = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu) VALUES ('$user', 966301)")
        fun insert(dim2: String, value: String = "1") =
            "INSERT INTO $schema.stat_daily (day, metric, dim1, dim2, value) VALUES ('2026-11-15', 'push', 'SYNTHETIC', '$dim2', $value)"

        execute(insert("SENT"))
        execute(insert(""))
        connection().use { connection ->
            connection.createStatement().use { sql ->
                assertSqlState(sql, "23505", insert("SENT"))
                assertSqlState(sql, "23514", insert("FAILED", "-1"))
                assertSqlState(sql, "23502", insert("FAILED", "NULL"))
                assertSqlState(sql, "22001", insert("x".repeat(65)))
            }
        }
        execute(
            "INSERT INTO $schema.stat_daily VALUES ('2026-11-15', 'push', 'SYNTHETIC', 'SENT', 2) " +
                "ON CONFLICT (day, metric, dim1, dim2) DO UPDATE SET value = stat_daily.value + excluded.value",
        )
        assertEquals(3, count("SELECT value FROM $schema.stat_daily WHERE dim2 = 'SENT'"))

        execute("DELETE FROM $schema.users WHERE id = '$user'")
        assertEquals(2, count("SELECT count(*) FROM $schema.stat_daily"))
    }
}
