package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V23__client_instances.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V23ClientInstancesTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("client_instances")

    @Test
    fun `instances belong to a user, are unique per kind and go with the account`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)
        val user = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu, name) VALUES ('$user', 966101, 'Synthetic user')")
        val instance = UUID.randomUUID()
        fun insert(kind: String, instanceId: UUID = instance, owner: UUID = user) =
            "INSERT INTO $schema.client_instances (id, user_id, kind, instance_id, version, custom, first_seen_at, last_seen_at) " +
                "VALUES ('${UUID.randomUUID()}', '$owner', '$kind', '$instanceId', '2.3.0', false, now(), now())"

        execute(insert("ANDROID"))
        execute(insert("IOS"))
        connection().use { connection ->
            connection.createStatement().use { sql ->
                assertSqlState(sql, "23505", insert("ANDROID"))
                assertSqlState(sql, "23514", insert("DESKTOP", UUID.randomUUID()))
                assertSqlState(sql, "23503", insert("WEB", UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        execute(
            "INSERT INTO $schema.client_daily VALUES ('2026-09-23', 'ANDROID', false, '2.3.0', '', '', 1, 1, 1)",
        )
        execute("DELETE FROM $schema.users WHERE id = '$user'")
        assertEquals(0, count("SELECT count(*) FROM $schema.client_instances"))
        assertEquals(1, count("SELECT count(*) FROM $schema.client_daily"))
    }
}
