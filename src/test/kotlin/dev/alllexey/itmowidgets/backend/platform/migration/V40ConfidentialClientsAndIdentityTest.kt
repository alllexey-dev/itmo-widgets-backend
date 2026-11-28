package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V40__confidential_clients_and_identity.sql`; the version is looked up by name, so a renumbering needs no edit here. */
class V40ConfidentialClientsAndIdentityTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("confidential_clients_and_identity")

    @Test
    fun `existing entries and connections keep what they had and gain no key, return URL or identity`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val user = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu, enrolled_at) VALUES ('$user', 966401, now())")
        val connection = UUID.randomUUID()
        execute(
            "INSERT INTO $schema.connections (id, user_id, client, client_id, name, scopes, created_at, approved_at) " +
                "VALUES ('$connection', '$user', 'MCP', 'itmo-mcp', 'nas', 'links:read', now(), now())",
        )

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        assertEquals(3, count("SELECT count(*) FROM $schema.connection_clients WHERE return_url IS NULL"))
        assertEquals(0, count("SELECT count(*) FROM $schema.connection_client_keys"))
        assertEquals(0, count("SELECT count(*) FROM $schema.connection_client_assertions"))
        assertEquals(10, count("SELECT count(*) FROM $schema.connection_client_grants"))
        assertEquals(
            1,
            count("SELECT count(*) FROM $schema.connections WHERE id = '$connection' AND NOT identity AND scopes = 'links:read'"),
        )
    }

    @Test
    fun `the new columns and tables hold their rules and the previous image still writes connections`() {
        withConstraintSchema(target = version) { s, sql, owner, _ ->
            // The previous image names no identity; a connection may now hold no scope.
            sql.executeUpdate(
                "INSERT INTO $s.connections (id, user_id, client, client_id, name, scopes, created_at, approved_at) " +
                    "VALUES ('${UUID.randomUUID()}', '$owner', 'MCP', 'itmo-mcp', 'nas', 'links:read', now(), now())",
            )
            sql.executeUpdate(
                "INSERT INTO $s.connections (id, user_id, client, client_id, name, scopes, identity, created_at, approved_at) " +
                    "VALUES ('${UUID.randomUUID()}', '$owner', 'WEB', 'web', 'Synthetic', '', true, now(), now())",
            )
            assertEquals(1, count("SELECT count(*) FROM $s.connections WHERE NOT identity AND scopes = 'links:read'"))

            sql.executeUpdate(
                "INSERT INTO $s.connection_clients (id, kind, name, deployment, auth, status, return_url, created_at, updated_at) " +
                    "VALUES ('synthetic-service', 'app', 'Synthetic', 'hosted', 'confidential', 'active', 'https://example.com/back', " +
                    "now(), now())",
            )
            fun client(id: String, returnUrl: String) =
                "INSERT INTO $s.connection_clients (id, kind, name, deployment, auth, status, return_url, created_at, updated_at) " +
                    "VALUES ('$id', 'app', 'X', 'hosted', 'public', 'active', $returnUrl, now(), now())"
            assertSqlState(sql, "23514", client("synthetic-http", "'http://example.com'"))
            assertSqlState(sql, "23514", client("synthetic-long", "'https://example.com/${"x".repeat(200)}'"))

            val key = "A".repeat(43)
            fun key(values: String) =
                "INSERT INTO $s.connection_client_keys (client_id, kid, public_key, created_at) VALUES ($values, now())"
            sql.executeUpdate(key("'synthetic-service', '2026-11', '$key'"))
            sql.executeUpdate(key("'synthetic-service', 'k.2_-', '$key'"))
            assertSqlState(sql, "23505", key("'synthetic-service', '2026-11', '$key'"))
            assertSqlState(sql, "23503", key("'unknown', 'k', '$key'"))
            assertSqlState(sql, "23514", key("'synthetic-service', 'bad kid', '$key'"))
            assertSqlState(sql, "23514", key("'synthetic-service', '${"k".repeat(65)}', '$key'"))
            assertSqlState(sql, "23514", key("'synthetic-service', 'k3', '${key.drop(1)}'"))
            assertSqlState(sql, "23514", key("'synthetic-service', 'k4', '${key.dropLast(1)}='"))

            fun used(values: String) = "INSERT INTO $s.connection_client_assertions (client_id, jti, expires_at) VALUES ($values)"
            sql.executeUpdate(used("'synthetic-service', 'a', now()"))
            assertSqlState(sql, "23505", used("'synthetic-service', 'a', now()"))
            assertSqlState(sql, "23514", used("'synthetic-service', '', now()"))
            assertSqlState(sql, "23514", used("'synthetic-service', '${"j".repeat(129)}', now()"))

            fun grant(id: String, position: Int) =
                "INSERT INTO $s.connection_client_grants (client_id, grant_id, required, requested_by_default, purpose, position) " +
                    "VALUES ('synthetic-service', '$id', false, true, NULL, $position)"
            sql.executeUpdate(grant("identity", 0))
            sql.executeUpdate(grant("itmo:refresh:student-personal-cabinet-dev", 1))
            assertSqlState(sql, "23514", grant("identity:name", 2))

            // Keys and used assertion ids go with their entry.
            sql.executeUpdate("DELETE FROM $s.connection_clients WHERE id = 'synthetic-service'")
            assertEquals(0, count("SELECT count(*) FROM $s.connection_client_keys"))
            assertEquals(0, count("SELECT count(*) FROM $s.connection_client_assertions"))
        }
    }
}
