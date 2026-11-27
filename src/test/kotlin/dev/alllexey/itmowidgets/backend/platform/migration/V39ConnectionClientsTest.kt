package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V39__connection_clients.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V39ConnectionClientsTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("connection_clients")

    @Test
    fun `the registry is seeded and connections and challenges are backfilled with their client and grants`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val user = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu, enrolled_at) VALUES ('$user', 966301, now())")
        val agent = connection(schema, user, "AGENT", "sport,friends:read", "a", sid = "'sid-1'")
        val mcp = connection(schema, user, "MCP", "links:read", "b", sid = "NULL", revoked = true)
        val web = connection(schema, user, "WEB", "user", "c", sid = "NULL")
        val connect = challenge(schema, "CONNECT", "AGENT", "sport,schedule:write", "ABCD2345")
        val renew = challenge(schema, "RENEW", "MCP", "links:read", "ABCD2346", connection = mcp)
        val webChallenge = challenge(schema, "CONNECT", "WEB", "user", "ABCD2347")

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        assertEquals(3, count("SELECT count(*) FROM $schema.connection_clients WHERE status = 'active' AND auth = 'public'"))
        assertEquals(
            1,
            count(
                "SELECT count(*) FROM $schema.connection_clients WHERE id = 'web' AND kind = 'web' AND deployment = 'hosted' " +
                    "AND name = 'ITMO.Widgets в браузере' AND max_per_user IS NULL",
            ),
        )
        assertEquals(10, count("SELECT count(*) FROM $schema.connection_client_grants"))
        assertEquals(
            1,
            count(
                "SELECT count(*) FROM $schema.connection_client_grants WHERE client_id = 'itmo-mcp' AND grant_id = 'itmo:sso' " +
                    "AND NOT required AND NOT requested_by_default AND position = 4",
            ),
        )
        assertEquals(
            1,
            count(
                "SELECT count(*) FROM $schema.connection_client_grants WHERE client_id = 'itmo-widgets-agent' " +
                    "AND grant_id = 'itmo:refresh:student-personal-cabinet' AND required AND requested_by_default " +
                    "AND purpose = 'чтобы записывать вас на физкультуру'",
            ),
        )

        assertEquals(
            1,
            count(
                connectionWhere(
                    schema,
                    agent,
                    "client_id = 'itmo-widgets-agent' AND itmo_grants = '{itmo:refresh:student-personal-cabinet}'",
                ),
            ),
        )
        assertEquals(1, count(connectionWhere(schema, mcp, "client_id = 'itmo-mcp' AND itmo_grants = '{}'")))
        assertEquals(1, count(connectionWhere(schema, web, "client_id = 'web' AND itmo_grants = '{}'")))

        assertEquals(
            1,
            count(
                challengeWhere(
                    schema,
                    connect,
                    "client_id = 'itmo-widgets-agent' AND protocol = 1 AND requested_grants = '[{\"id\": \"widgets:sport\", " +
                        "\"required\": false}, {\"id\": \"widgets:schedule:write\", \"required\": false}, " +
                        "{\"id\": \"itmo:refresh:student-personal-cabinet\", \"required\": true}]'::jsonb",
                ),
            ),
        )
        assertEquals(
            1,
            count(
                challengeWhere(
                    schema,
                    renew,
                    "client_id = 'itmo-mcp' AND requested_grants = " +
                        "'[{\"id\": \"itmo:refresh:student-personal-cabinet\", \"required\": true}]'::jsonb",
                ),
            ),
        )
        assertEquals(
            1,
            count(
                challengeWhere(
                    schema,
                    webChallenge,
                    "client_id = 'web' AND requested_grants = '[{\"id\": \"widgets:user\", \"required\": true}]'::jsonb",
                ),
            ),
        )
    }

    @Test
    fun `the previous image still writes rows and the new columns hold their rules`() {
        withConstraintSchema(target = version) { s, sql, owner, _ ->
            // The previous image names neither the client id nor the grants; a new kind is allowed.
            val old = UUID.randomUUID()
            sql.executeUpdate(
                "INSERT INTO $s.connections (id, user_id, client, name, scopes, created_at, approved_at) " +
                    "VALUES ('$old', '$owner', 'AGENT', 'nas', 'sport', now(), now())",
            )
            assertEquals(1, count(connectionWhere(s, old, "client_id IS NULL AND itmo_grants = '{}'")))
            sql.executeUpdate(
                "INSERT INTO $s.connection_clients (id, kind, name, deployment, auth, status, created_at, updated_at) " +
                    "VALUES ('synthetic-app', 'app', 'Synthetic', 'hosted', 'public', 'active', now(), now())",
            )
            sql.executeUpdate(
                "INSERT INTO $s.connections (id, user_id, client, client_id, name, scopes, created_at, approved_at) " +
                    "VALUES ('${UUID.randomUUID()}', '$owner', 'APP', 'synthetic-app', 'Synthetic', 'user', now(), now())",
            )
            val challenge = "INSERT INTO $s.connection_pair_challenges (id, kind, client, code, claim_secret_hash, key_commitment, " +
                "name, scopes, status, client_ip, created_at, expires_at"

            fun client(values: String) = "INSERT INTO $s.connection_clients (id, kind, name, deployment, auth, status, " +
                "created_at, updated_at) VALUES ($values, now(), now())"
            assertSqlState(sql, "23514", client("'Bad Id', 'app', 'X', 'hosted', 'public', 'active'"))
            assertSqlState(sql, "23514", client("'bad-kind', 'desktop', 'X', 'hosted', 'public', 'active'"))
            assertSqlState(sql, "23514", client("'bad-deployment', 'app', 'X', 'cloud', 'public', 'active'"))
            assertSqlState(sql, "23514", client("'bad-status', 'app', 'X', 'hosted', 'public', 'paused'"))
            assertSqlState(sql, "23514", client("'no-name', 'app', '', 'hosted', 'public', 'active'"))
            assertSqlState(sql, "23505", client("'web', 'web', 'X', 'hosted', 'public', 'active'"))

            fun grant(values: String) =
                "INSERT INTO $s.connection_client_grants (client_id, grant_id, required, requested_by_default, purpose, position) " +
                    "VALUES ($values)"
            assertSqlState(sql, "23503", grant("'unknown', 'widgets:user', true, true, NULL, 0"))
            assertSqlState(sql, "23514", grant("'synthetic-app', 'cookie', true, true, NULL, 0"))
            assertSqlState(sql, "23505", grant("'web', 'widgets:sport', false, false, NULL, 0"))
            assertSqlState(sql, "23505", grant("'web', 'widgets:user', false, false, NULL, 1"))

            assertSqlState(
                sql,
                "23503",
                "INSERT INTO $s.connections (id, user_id, client, client_id, name, scopes, created_at, approved_at) " +
                    "VALUES ('${UUID.randomUUID()}', '$owner', 'WEB', 'unknown', 'X', 'user', now(), now())",
            )
            assertSqlState(sql, "23514", "UPDATE $s.connections SET client = 'DESKTOP' WHERE id = '$old'")
            fun values(code: String, client: String = "APP") = " VALUES ('${UUID.randomUUID()}', 'CONNECT', '$client', '$code', " +
                "'${"b".repeat(64)}', decode(repeat('00', 32), 'hex'), 'Synthetic', 'user', 'PENDING', '203.0.113.7', now(), " +
                "now() + interval '2 minutes'"
            sql.executeUpdate("$challenge, client_id, protocol, requested_grants)${values("ABCD2399")}, 'synthetic-app', 2, '[]')")
            assertSqlState(sql, "23514", "$challenge, protocol)${values("ABCD2398")}, 3)")
            assertSqlState(sql, "23514", "$challenge, requested_grants)${values("ABCD2397")}, '{}')")
            // The previous image's challenge: protocol 1 by default.
            sql.executeUpdate("$challenge)${values("ABCD2396", "WEB")})")
            assertEquals(1, count("SELECT count(*) FROM $s.connection_pair_challenges WHERE code = 'ABCD2396' AND protocol = 1"))
        }
    }

    private fun connection(
        schema: String,
        user: UUID,
        client: String,
        scopes: String,
        hash: String,
        sid: String,
        revoked: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        execute(
            "INSERT INTO $schema.connections (id, user_id, client, name, scopes, token_hash, created_at, approved_at, revoked_at, " +
                "itmo_sid, itmo_expires_at) VALUES ('$id', '$user', '$client', 'nas', '$scopes', '${hash.repeat(64)}', now(), now(), " +
                "${if (revoked) "now()" else "NULL"}, $sid, ${if (sid == "NULL") "NULL" else "now() + interval '90 days'"})",
        )
        return id
    }

    private fun challenge(schema: String, kind: String, client: String, scopes: String, code: String, connection: UUID? = null): UUID {
        val id = UUID.randomUUID()
        execute(
            "INSERT INTO $schema.connection_pair_challenges (id, kind, client, code, claim_secret_hash, key_commitment, name, scopes, " +
                "status, client_ip, created_at, expires_at, connection_id) VALUES ('$id', '$kind', '$client', '$code', " +
                "'${"b".repeat(64)}', decode(repeat('00', 32), 'hex'), 'nas', '$scopes', 'PENDING', '203.0.113.7', now(), " +
                "now() + interval '2 minutes', ${connection?.let { "'$it'" } ?: "NULL"})",
        )
        return id
    }

    private fun connectionWhere(schema: String, id: UUID, condition: String) =
        "SELECT count(*) FROM $schema.connections WHERE id = '$id' AND $condition"

    private fun challengeWhere(schema: String, id: UUID, condition: String) =
        "SELECT count(*) FROM $schema.connection_pair_challenges WHERE id = '$id' AND $condition"
}
