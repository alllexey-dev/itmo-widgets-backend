package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V35__drop_old_table_names.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V35DropOldTableNamesTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("drop_old_table_names")

    @Test
    fun `every old table name is gone and its table keeps the rows`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val user = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu) VALUES ('$user', 920001)")
        execute("INSERT INTO $schema.user_active_weeks(user_id, week_start) VALUES ('$user', DATE '2026-09-21')")
        execute("INSERT INTO $schema.isu_flows(potok_id) VALUES (93724)")

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        for ((old, new) in V34TableNamesTest.RENAMED) {
            assertEquals(0, count("SELECT count(*) FROM pg_class WHERE relnamespace = '$schema'::regnamespace AND relname = '$old'"), old)
            assertEquals(
                1,
                count("SELECT count(*) FROM pg_class WHERE relnamespace = '$schema'::regnamespace AND relname = '$new' AND relkind = 'r'"),
                new,
            )
        }
        assertEquals(1, count("SELECT count(*) FROM $schema.stats_user_active_weeks WHERE user_id = '$user'"))
        assertEquals(1, count("SELECT count(*) FROM $schema.isu_flows WHERE potok_id = 93724"))
    }
}
