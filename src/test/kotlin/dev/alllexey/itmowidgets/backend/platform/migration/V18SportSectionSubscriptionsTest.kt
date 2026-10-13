package dev.alllexey.itmowidgets.backend.platform.migration

import dev.alllexey.itmowidgets.backend.platform.PostgreSqlTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V18__sport_section_subscriptions.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V18SportSectionSubscriptionsTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("sport_section_subscriptions")

    @Test
    fun `subscriptions keep their states, one active per slot and user, and occurrences are handled once`() {
        withConstraintSchema(target = version) { schema, sql, owner, friend ->
            sql.executeUpdate(insertSql(schema, "sport_auto_sign_entries", autoFields(owner) + ("is_cancelled" to "false")))
            val entry = count("SELECT id FROM $schema.sport_auto_sign_entries")
            fun subscription(values: Map<String, String> = emptyMap()) = insertSql(
                schema,
                "sport_section_subscriptions",
                mapOf(
                    "user_id" to "'$owner'",
                    "slot_key" to "'1|1|b1|1|1|1|1|1|0|3600000'",
                    "source_lesson_id" to "1",
                    "last_opened_ends_at" to SQL_PREDICTED_END,
                    "created_at" to SQL_START,
                ) + values,
            )
            assertEquals(1, sql.executeUpdate(subscription()))
            assertSqlState(sql, "23505", subscription())
            assertEquals(1, sql.executeUpdate(subscription(mapOf("user_id" to "'$friend'"))))
            val stopped = mapOf("status" to "'STOPPED'", "stop_reason" to "'USER'", "stopped_at" to SQL_START)
            assertEquals(1, sql.executeUpdate(subscription(stopped)))
            assertEquals(1, sql.executeUpdate(subscription(stopped + ("stop_reason" to "'EXPIRED'"))))
            for (invalid in listOf(
                stopped + ("status" to "'PAUSED'"),
                stopped + ("stop_reason" to "'GONE'"),
                mapOf("user_id" to "'$friend'", "slot_key" to "'other'", "stop_reason" to "'USER'", "stopped_at" to SQL_START),
                stopped + ("stopped_at" to "NULL"),
            )) {
                assertSqlState(sql, "23514", subscription(invalid))
            }
            assertSqlState(sql, "23503", subscription(mapOf("user_id" to "'${UUID.randomUUID()}'")))
            assertSqlState(sql, "23503", subscription(stopped + ("source_lesson_id" to "2")))

            val subscriptionId = count("SELECT id FROM $schema.sport_section_subscriptions WHERE user_id = '$owner' AND status = 'ACTIVE'")
            fun occurrence(values: Map<String, String> = emptyMap()) = insertSql(
                schema,
                "sport_section_occurrences",
                mapOf(
                    "subscription_id" to "$subscriptionId",
                    "starts_at" to SQL_PREDICTED_START,
                    "ends_at" to SQL_PREDICTED_END,
                    "state" to "'QUEUED'",
                    "entry_id" to "$entry",
                ) + values,
            )
            assertEquals(1, sql.executeUpdate(occurrence()))
            assertSqlState(sql, "23505", occurrence(mapOf("state" to "'SKIPPED'", "entry_id" to "NULL")))
            assertEquals(
                1,
                sql.executeUpdate(
                    occurrence(
                        mapOf(
                            "starts_at" to SQL_START,
                            "ends_at" to SQL_END,
                            "state" to "'LIMIT'",
                            "entry_id" to "NULL",
                        ),
                    ),
                ),
            )
            assertSqlState(sql, "23514", occurrence(mapOf("starts_at" to SQL_END, "ends_at" to SQL_START)))
            assertSqlState(sql, "23514", occurrence(mapOf("starts_at" to SQL_END, "ends_at" to SQL_PREDICTED_END, "state" to "'LATE'")))

            sql.executeUpdate("DELETE FROM $schema.sport_auto_sign_entries WHERE id = $entry")
            assertEquals(1, count("SELECT count(*) FROM $schema.sport_section_occurrences WHERE state = 'QUEUED' AND entry_id IS NULL"))
            sql.executeUpdate("DELETE FROM $schema.sport_section_subscriptions WHERE id = $subscriptionId")
            assertEquals(0, count("SELECT count(*) FROM $schema.sport_section_occurrences"))

            assertEquals(1, sql.executeUpdate("UPDATE $schema.sport_lessons SET places_limit = 24 WHERE id = 1"))
            assertEquals(24, count("SELECT places_limit FROM $schema.sport_lessons WHERE id = 1"))
        }
    }

    @Test
    fun `runs as an application role without the TEMP privilege`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        // Like the deployed database: the application role owns the tables it created and has no TEMP.
        val role = "app_" + schema.takeLast(12)
        val database = PostgreSqlTestDatabase.container.databaseName
        execute("CREATE ROLE $role LOGIN PASSWORD 'synthetic'")
        execute("REVOKE TEMP ON DATABASE $database FROM PUBLIC")
        execute("GRANT USAGE, CREATE ON SCHEMA $schema TO $role")
        execute("GRANT ALL ON ALL TABLES IN SCHEMA $schema TO $role")
        execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA $schema TO $role")
        // ALTER TABLE needs ownership; the deployed application role owns every table it created.
        execute("ALTER TABLE $schema.sport_lessons OWNER TO $role")
        try {
            val asApplication = Flyway.configure()
                .configuration(isolatedFlyway(schema, target = version).configuration)
                .dataSource(PostgreSqlTestDatabase.container.jdbcUrl, role, "synthetic")
                .load()

            assertEquals(1, asApplication.migrate().migrationsExecuted)
        } finally {
            execute("GRANT TEMP ON DATABASE $database TO PUBLIC")
        }
    }
}
