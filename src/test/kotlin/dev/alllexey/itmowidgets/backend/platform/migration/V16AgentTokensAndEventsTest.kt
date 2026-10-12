package dev.alllexey.itmowidgets.backend.platform.migration

import dev.alllexey.itmowidgets.backend.platform.PostgreSqlTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V16__agent_tokens_and_events.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V16AgentTokensAndEventsTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("agent_tokens_and_events")

    @Test
    fun `tokens, events and the per-user sequence keep their rules and go with the user`() {
        withConstraintSchema(target = version) { schema, sql, owner, friend ->
            fun token(values: Map<String, String> = emptyMap()) = insertSql(
                schema,
                "agent_tokens",
                mapOf(
                    "id" to "'${UUID.randomUUID()}'",
                    "user_id" to "'$owner'",
                    "name" to "'Synthetic agent'",
                    "token_hash" to "'${UUID.randomUUID().toString().replace("-", "").padEnd(64, '0')}'",
                    "token_hint" to "'synthint'",
                    "scopes" to "'sport'",
                    "created_via" to "'PAIR'",
                    "created_at" to SQL_START,
                ) + values,
            )
            fun event(seq: Long, key: String, user: UUID = owner) = insertSql(
                schema,
                "agent_events",
                mapOf(
                    "user_id" to "'$user'",
                    "seq" to "$seq",
                    "dedup_key" to "'$key'",
                    "type" to "'FRIENDSHIP_EVENT_PAYLOAD'",
                    "payload" to "'{\"event\":\"REQUEST_RECEIVED\"}'",
                    "created_at" to SQL_START,
                    "expires_at" to SQL_END,
                ),
            )
            assertEquals(1, sql.executeUpdate(token()))
            for (invalid in listOf(mapOf("created_via" to "'CLI'"), mapOf("scopes" to "''"), mapOf("name" to "''"))) {
                assertSqlState(sql, "23514", token(invalid))
            }
            assertSqlState(sql, "23505", token(mapOf("token_hash" to "'${"a".repeat(64)}'")).also { sql.executeUpdate(it) })

            assertEquals(1, sql.executeUpdate(event(1, "sport-free:1:1")))
            assertSqlState(sql, "23505", event(1, "sport-free:1:2"))
            assertSqlState(sql, "23505", event(2, "sport-free:1:1"))
            assertEquals(1, sql.executeUpdate(event(1, "sport-free:1:1", user = friend)))
            assertSqlState(sql, "23514", event(0, "zero"))

            assertEquals(1, sql.executeUpdate("INSERT INTO $schema.agent_users (user_id) VALUES ('$owner')"))
            assertEquals(0, count("SELECT last_event_seq FROM $schema.agent_users WHERE user_id = '$owner'"))
            assertSqlState(sql, "23514", "UPDATE $schema.agent_users SET last_event_seq = -1 WHERE user_id = '$owner'")

            sql.executeUpdate("DELETE FROM $schema.users WHERE id = '$owner'")
            for (table in listOf("agent_tokens", "agent_events", "agent_users")) {
                assertEquals(0, count("SELECT count(*) FROM $schema.$table WHERE user_id = '$owner'"), table)
            }
            assertEquals(1, count("SELECT count(*) FROM $schema.agent_events WHERE user_id = '$friend'"))
        }
    }

    @Test
    fun `runs as an application role without the TEMP privilege`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        // Like the deployed database: the application role owns nothing outside its grants and has no TEMP.
        val role = "app_" + schema.takeLast(12)
        val database = PostgreSqlTestDatabase.container.databaseName
        execute("CREATE ROLE $role LOGIN PASSWORD 'synthetic'")
        execute("REVOKE TEMP ON DATABASE $database FROM PUBLIC")
        execute("GRANT USAGE, CREATE ON SCHEMA $schema TO $role")
        execute("GRANT ALL ON ALL TABLES IN SCHEMA $schema TO $role")
        execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA $schema TO $role")
        try {
            val asApplication = Flyway.configure()
                .configuration(isolatedFlyway(schema, target = version).configuration)
                .dataSource(PostgreSqlTestDatabase.container.jdbcUrl, role, "synthetic")
                .load()

            assertEquals(1, asApplication.migrate().migrationsExecuted)
            assertEquals(0, count("SELECT count(*) FROM $schema.agent_tokens"))
        } finally {
            execute("GRANT TEMP ON DATABASE $database TO PUBLIC")
        }
    }
}
