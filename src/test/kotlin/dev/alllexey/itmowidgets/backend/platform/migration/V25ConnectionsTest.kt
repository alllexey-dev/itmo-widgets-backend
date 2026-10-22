package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V25__connections.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V25ConnectionsTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("connections")

    @Test
    fun `existing users are enrolled, agent grants carry over and the new tables hold their rules`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val user = UUID.randomUUID()
        val other = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu, name) VALUES ('$user', 966201, 'Synthetic user')")
        execute("INSERT INTO $schema.users(id, isu, name) VALUES ('$other', 966202, 'Synthetic user')")
        execute("INSERT INTO $schema.agent_users(user_id, access_granted_at) VALUES ('$user', now())")
        execute("INSERT INTO $schema.agent_users(user_id) VALUES ('$other')")

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        assertEquals(2, count("SELECT count(*) FROM $schema.users WHERE enrolled_at = created_at AND consent_version IS NULL"))
        assertEquals(1, count("SELECT count(*) FROM $schema.client_users WHERE user_id = '$user' AND agent_access_granted_at IS NOT NULL"))
        assertEquals(0, count("SELECT count(*) FROM $schema.client_users WHERE user_id = '$other'"))

        fun insertConnection(client: String, hash: String = "a".repeat(64), sid: String = "NULL", expires: String = "NULL") =
            "INSERT INTO $schema.connections (id, user_id, client, name, scopes, token_hash, created_at, approved_at, itmo_sid, " +
                "itmo_expires_at) VALUES ('${UUID.randomUUID()}', '$user', '$client', 'nas', 'sport', '$hash', now(), now(), $sid, $expires)"
        execute(insertConnection("AGENT", sid = "'sid-1'", expires = "now() + interval '90 days'"))
        fun challenge(commitment: Int, code: String = "ABCD2345") =
            "INSERT INTO $schema.pair_challenges (id, kind, client, code, claim_secret_hash, key_commitment, name, scopes, status, " +
                "client_ip, created_at, expires_at) VALUES ('${UUID.randomUUID()}', 'CONNECT', 'WEB', '$code', '${"b".repeat(64)}', " +
                "decode(repeat('00', $commitment), 'hex'), 'Chrome', 'user', 'PENDING', '203.0.113.7', now(), now() + interval '2 minutes')"
        execute(challenge(32))
        connection().use { connection ->
            connection.createStatement().use { sql ->
                assertSqlState(sql, "23505", insertConnection("MCP"))
                assertSqlState(sql, "23514", insertConnection("DESKTOP", "c".repeat(64)))
                assertSqlState(sql, "23514", insertConnection("MCP", "d".repeat(64), sid = "'sid-2'"))
                assertSqlState(sql, "23514", challenge(31, "ABCD2346"))
                assertSqlState(sql, "23505", challenge(32))
                assertSqlState(
                    sql,
                    "23503",
                    "INSERT INTO $schema.client_events VALUES ('${UUID.randomUUID()}', 1, 'k', 't', '{}', now(), now())",
                )
            }
        }
        execute(
            "INSERT INTO $schema.client_instances (id, user_id, kind, instance_id, version, custom, first_seen_at, last_seen_at, " +
                "itmo_sid) VALUES ('${UUID.randomUUID()}', '$user', 'MCP', '${UUID.randomUUID()}', '0.4.0', false, now(), now(), 'sid-3')",
        )
        execute("DELETE FROM $schema.users WHERE id = '$user'")
        assertEquals(0, count("SELECT count(*) FROM $schema.connections"))
        assertEquals(0, count("SELECT count(*) FROM $schema.client_users"))
    }
}
