package dev.alllexey.itmowidgets.backend.platform.migration

import dev.alllexey.itmowidgets.backend.platform.PostgreSqlTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V17__pairing.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V17PairingTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("pairing")

    @Test
    fun `existing web logins become web sign-ins and keyed kinds keep their rules`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val old = UUID.randomUUID()
        execute(
            "INSERT INTO $schema.web_login_challenges (id, code, poll_secret_hash, status, client_ip, created_at, expires_at) " +
                "VALUES ('$old', 'OLD23456', '${"a".repeat(64)}', 'PENDING', '203.0.113.1', $SQL_START, $SQL_END)",
        )
        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)
        assertEquals(1, count("SELECT count(*) FROM $schema.web_login_challenges WHERE id = '$old' AND kind = 'WEB_SESSION'"))

        withConstraintSchema(target = version) { s, sql, owner, _ ->
            val token = UUID.randomUUID()
            sql.executeUpdate(
                "INSERT INTO $s.agent_tokens (id, user_id, name, token_hash, token_hint, scopes, created_via, created_at) " +
                    "VALUES ('$token', '$owner', 'nas', '${"b".repeat(64)}', 'hint', 'sport', 'PAIR', $SQL_START)",
            )
            var code = 0
            fun challenge(values: Map<String, String>) = insertSql(
                s,
                "web_login_challenges",
                mapOf(
                    "id" to "'${UUID.randomUUID()}'", "code" to "'C${(++code).toString().padStart(7, '2')}'",
                    "poll_secret_hash" to "'${"c".repeat(64)}'", "status" to "'PENDING'", "client_ip" to "'203.0.113.1'",
                    "created_at" to SQL_START, "expires_at" to SQL_END, "kind" to "'AGENT'",
                    "key_commitment" to "decode(repeat('ab', 32), 'hex')",
                ) + values,
            )
            assertEquals(1, sql.executeUpdate(challenge(emptyMap())))
            assertEquals(1, sql.executeUpdate(challenge(mapOf("kind" to "'WEB_SESSION'", "key_commitment" to "NULL"))))
            assertEquals(1, sql.executeUpdate(challenge(mapOf("kind" to "'AGENT_SESSION'", "agent_token_id" to "'$token'"))))
            assertEquals(1, sql.executeUpdate(challenge(mapOf("status" to "'REJECTED'"))))
            assertEquals(1, sql.executeUpdate(challenge(mapOf("sealed_bundle" to "decode(repeat('cd', 48), 'hex')"))))
            for (invalid in listOf(
                mapOf("kind" to "'BROWSER'"),
                mapOf("key_commitment" to "NULL"),
                mapOf("key_commitment" to "decode(repeat('ab', 31), 'hex')"),
                mapOf("kind" to "'WEB_SESSION'"),
                mapOf("kind" to "'AGENT_SESSION'"),
                mapOf("agent_token_id" to "'$token'"),
                mapOf("sealed_bundle" to "decode(repeat('cd', 47), 'hex')"),
                mapOf("sealed_bundle" to "decode(repeat('cd', 16385), 'hex')"),
                mapOf("opened_by" to "'$owner'"),
                mapOf("status" to "'OPENED'"),
            )) {
                assertSqlState(sql, "23514", challenge(invalid))
            }
            // Deleting the token deletes its session hand-offs; deleting the opener deletes what it opened.
            assertEquals(1, sql.executeUpdate(challenge(mapOf("opened_by" to "'$owner'", "opened_at" to SQL_START))))
            sql.executeUpdate("DELETE FROM $s.agent_tokens WHERE id = '$token'")
            assertEquals(0, count("SELECT count(*) FROM $s.web_login_challenges WHERE kind = 'AGENT_SESSION'"))
            sql.executeUpdate("DELETE FROM $s.users WHERE id = '$owner'")
            assertEquals(0, count("SELECT count(*) FROM $s.web_login_challenges WHERE opened_by IS NOT NULL"))
        }
    }

    @Test
    fun `runs as an application role without the TEMP privilege`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val role = "app_" + schema.takeLast(12)
        val database = PostgreSqlTestDatabase.container.databaseName
        execute("CREATE ROLE $role LOGIN PASSWORD 'synthetic'")
        execute("REVOKE TEMP ON DATABASE $database FROM PUBLIC")
        execute("GRANT USAGE, CREATE ON SCHEMA $schema TO $role")
        execute("GRANT ALL ON ALL TABLES IN SCHEMA $schema TO $role")
        execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA $schema TO $role")
        // ALTER TABLE needs ownership; the deployed application role owns every table it created.
        execute("ALTER TABLE $schema.web_login_challenges OWNER TO $role")
        execute("ALTER TABLE $schema.agent_tokens OWNER TO $role")
        execute("ALTER TABLE $schema.users OWNER TO $role")
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
