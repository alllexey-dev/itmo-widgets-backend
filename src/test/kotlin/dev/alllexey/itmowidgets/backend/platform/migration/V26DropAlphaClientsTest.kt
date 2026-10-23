package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V26__drop_alpha_clients.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V26DropAlphaClientsTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("drop_alpha_clients")

    @Test
    fun `the alpha tables and the statistics rows keyed by them go, app installs stay`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val user = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu, name) VALUES ('$user', 966301, 'Synthetic user')")
        for (kind in listOf("ANDROID", "WEB", "AGENT", "MCP")) {
            execute(
                "INSERT INTO $schema.client_instances (id, user_id, kind, instance_id, version, custom, first_seen_at, last_seen_at) " +
                    "VALUES ('${UUID.randomUUID()}', '$user', '$kind', '${UUID.randomUUID()}', '1.0.0', false, now(), now())",
            )
        }

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        assertEquals(
            0,
            count(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = '$schema' " +
                    "AND table_name IN ('web_login_challenges', 'web_sessions', 'agent_events', 'agent_tokens', 'agent_users')",
            ),
        )
        assertEquals(2, count("SELECT count(*) FROM $schema.client_instances WHERE kind IN ('ANDROID', 'MCP')"))
        assertEquals(2, count("SELECT count(*) FROM $schema.client_instances"))
    }
}
