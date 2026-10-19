package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V22__web_session_refresh.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V22WebSessionRefreshTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("web_session_refresh")

    @Test
    fun `existing sessions keep their cookie and refresh hashes are unique`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val user = UUID.randomUUID()
        val session = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu, name) VALUES ('$user', 964101, 'Synthetic user')")
        execute(
            "INSERT INTO $schema.web_sessions(id, user_id, token_hash, created_at, last_seen_at, expires_at) " +
                "VALUES ('$session', '$user', '${"a".repeat(64)}', now(), now(), now() + interval '60 days')",
        )

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        assertEquals(
            1,
            count(
                "SELECT count(*) FROM $schema.web_sessions WHERE id = '$session' AND access_expires_at IS NULL " +
                    "AND refresh_hash IS NULL AND previous_refresh_hash IS NULL AND rotated_at IS NULL",
            ),
        )
        execute("UPDATE $schema.web_sessions SET refresh_hash = '${"b".repeat(64)}', previous_refresh_hash = '${"c".repeat(64)}'")
        val second = "INSERT INTO $schema.web_sessions(id, user_id, token_hash, created_at, last_seen_at, expires_at, %s) " +
            "VALUES ('${UUID.randomUUID()}', '$user', '${"d".repeat(64)}', now(), now(), now() + interval '1 day', '%s')"
        connection().use { connection ->
            connection.createStatement().use { sql ->
                assertSqlState(sql, "23505", second.format("refresh_hash", "b".repeat(64)))
                assertSqlState(sql, "23505", second.format("previous_refresh_hash", "c".repeat(64)))
            }
        }
    }
}
