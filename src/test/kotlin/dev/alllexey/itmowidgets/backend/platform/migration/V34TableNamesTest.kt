package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `V34__table_names.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V34TableNamesTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("table_names")

    @Test
    fun `every renamed table keeps its rows and its old name stays as a view of it`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val user = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu) VALUES ('$user', 920001)")
        execute("INSERT INTO $schema.user_active_weeks(user_id, week_start) VALUES ('$user', DATE '2026-09-21')")
        execute("INSERT INTO $schema.isu_potoks(potok_id) VALUES (93724)")

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        for ((old, new) in RENAMED) {
            assertEquals(
                listOf("r"),
                strings("SELECT relkind::text FROM pg_class WHERE relnamespace = '$schema'::regnamespace AND relname = '$new'"),
                new,
            )
            assertEquals(
                listOf("v"),
                strings("SELECT relkind::text FROM pg_class WHERE relnamespace = '$schema'::regnamespace AND relname = '$old'"),
                old,
            )
            assertEquals(columns(schema, new), columns(schema, old), old)
        }
        assertEquals(1, count("SELECT count(*) FROM $schema.stats_user_active_weeks WHERE user_id = '$user'"))
        assertEquals(1, count("SELECT count(*) FROM $schema.user_active_weeks WHERE user_id = '$user'"))
        assertEquals(1, count("SELECT count(*) FROM $schema.isu_flows WHERE potok_id = 93724"))
    }

    /** The statements are the previous image's: a rollback to it must keep working on this schema. */
    @Test
    fun `the previous image upserts, inserts, locks and deletes through the old names`() {
        withConstraintSchema(target = version) { schema, sql, owner, _ ->
            fun flow(lastSeen: String) =
                """
                INSERT INTO $schema.user_subject_flows (user_id, subject_id, period_key, flow_id, group_name, type_id, last_seen)
                VALUES ('$owner', 501, '2026-1', 93724, 'Flow', 1, DATE '$lastSeen')
                ON CONFLICT (user_id, subject_id, period_key, flow_id) DO UPDATE SET
                    group_name = EXCLUDED.group_name,
                    type_id = EXCLUDED.type_id,
                    last_seen = GREATEST(user_subject_flows.last_seen, EXCLUDED.last_seen)
                """.trimIndent()
            assertEquals(1, sql.executeUpdate(flow("2026-10-05")))
            assertEquals(1, sql.executeUpdate(flow("2026-09-28")))
            assertEquals(listOf("2026-10-05"), strings("SELECT last_seen::text FROM $schema.schedule_flows WHERE user_id = '$owner'"))

            // An identity key comes back through the view, as Hibernate reads it after an insert.
            sql.executeQuery("INSERT INTO $schema.user_sport_lessons(user_id, lesson_id) VALUES ('$owner', 1) RETURNING id").use {
                assertTrue(it.next())
                assertTrue(it.getLong(1) > 0)
            }
            assertEquals(1, count("SELECT count(*) FROM $schema.sport_bookings WHERE user_id = '$owner'"))
            sql.executeQuery("SELECT id FROM $schema.user_sport_lessons WHERE user_id = '$owner' FOR UPDATE").use { assertTrue(it.next()) }

            // The table's constraints answer through the view with the same states.
            assertSqlState(sql, "23503", "INSERT INTO $schema.user_sport_lessons(user_id, lesson_id) VALUES ('$owner', 999)")
            assertEquals(
                1,
                sql.executeUpdate("INSERT INTO $schema.user_active_weeks(user_id, week_start) VALUES ('$owner', DATE '2026-09-21')"),
            )
            assertSqlState(sql, "23505", "INSERT INTO $schema.user_active_weeks(user_id, week_start) VALUES ('$owner', DATE '2026-09-21')")
            assertSqlState(sql, "23514", "INSERT INTO $schema.user_active_weeks(user_id, week_start) VALUES ('$owner', DATE '2026-09-22')")

            assertEquals(1, sql.executeUpdate("DELETE FROM $schema.user_sport_lessons WHERE user_id = '$owner'"))
            assertEquals(0, count("SELECT count(*) FROM $schema.sport_bookings"))
        }
    }

    private fun columns(schema: String, table: String): List<String> = strings(
        """
        SELECT column_name || ' ' || data_type FROM information_schema.columns
        WHERE table_schema = '$schema' AND table_name = '$table' ORDER BY ordinal_position
        """.trimIndent(),
    ).also { assertTrue(it.isNotEmpty(), table) }

    private fun strings(sql: String): List<String> = connection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    companion object {
        val RENAMED = mapOf(
            "lessons" to "schedule_lessons",
            "user_subject_flows" to "schedule_flows",
            "isu_potoks" to "isu_flows",
            "isu_potok_members" to "isu_flow_members",
            "isu_potok_teachers" to "isu_flow_teachers",
            "groups" to "study_groups",
            "user_groups" to "user_study_groups",
            "user_sport_lessons" to "sport_bookings",
            "sport_section_subscriptions" to "sport_slot_subscriptions",
            "sport_section_occurrences" to "sport_slot_occurrences",
            "devices" to "push_devices",
            "client_instances" to "client_installs",
            "client_users" to "connection_users",
            "client_events" to "connection_events",
            "pair_challenges" to "connection_pair_challenges",
            "stat_daily" to "stats_daily",
            "people_daily" to "stats_people_daily",
            "client_daily" to "stats_clients_daily",
            "user_active_weeks" to "stats_user_active_weeks",
        )
    }
}
