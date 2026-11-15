package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V32__activity_history.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V32ActivityHistoryTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("activity_history")

    @Test
    fun `the migration adds both tables empty`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)
        assertEquals(0, count("SELECT count(*) FROM $schema.people_daily"))
        assertEquals(0, count("SELECT count(*) FROM $schema.user_active_weeks"))
    }

    @Test
    fun `an activity week is a Monday, once per account, and goes with the account`() {
        withConstraintSchema { schema, sql, owner, friend ->
            fun insert(user: UUID, week: String) =
                "INSERT INTO $schema.user_active_weeks (user_id, week_start) VALUES ('$user', DATE '$week')"

            assertEquals(1, sql.executeUpdate(insert(owner, "2026-09-21")))
            assertEquals(1, sql.executeUpdate(insert(owner, "2026-09-28")))
            assertEquals(1, sql.executeUpdate(insert(friend, "2026-09-21")))
            assertSqlState(sql, "23505", insert(owner, "2026-09-21"))
            assertSqlState(sql, "23514", insert(owner, "2026-09-22"))
            assertSqlState(sql, "23514", insert(owner, "2026-09-27"))
            assertSqlState(sql, "23503", insert(UUID.randomUUID(), "2026-09-21"))

            sql.executeUpdate("DELETE FROM $schema.users WHERE id = '$owner'")
            assertEquals(1, count("SELECT count(*) FROM $schema.user_active_weeks"))
            assertEquals(1, count("SELECT count(*) FROM $schema.user_active_weeks WHERE user_id = '$friend'"))
        }
    }

    @Test
    fun `a people day has one row per day with nested windows`() {
        withConstraintSchema { schema, sql, _, _ ->
            fun insert(day: String, d1: Int, d7: Int, d30: Int) =
                "INSERT INTO $schema.people_daily (day, active_1d, active_7d, active_30d) VALUES (DATE '$day', $d1, $d7, $d30)"

            assertEquals(1, sql.executeUpdate(insert("2026-09-21", 3, 5, 9)))
            assertEquals(1, sql.executeUpdate(insert("2026-09-22", 0, 0, 0)))
            assertSqlState(sql, "23505", insert("2026-09-21", 1, 1, 1))
            assertSqlState(sql, "23514", insert("2026-09-23", -1, 0, 0))
            assertSqlState(sql, "23514", insert("2026-09-23", 2, 1, 3))
            assertSqlState(sql, "23514", insert("2026-09-23", 1, 3, 2))
        }
    }
}
