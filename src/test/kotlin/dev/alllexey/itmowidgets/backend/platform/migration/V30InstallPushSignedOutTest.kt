package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V30__install_push_signed_out.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V30InstallPushSignedOutTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("install_push_signed_out")

    @Test
    fun `existing installs are never signed out, and a row written the way an older image writes it stays so`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val user = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu, name) VALUES ('$user', 966501, 'Synthetic user')")
        val existing = UUID.randomUUID()
        execute(instance(schema, user, existing))

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        val older = UUID.randomUUID()
        execute(instance(schema, user, older))
        assertEquals(2, count("SELECT count(*) FROM $schema.client_instances WHERE push_signed_out_at IS NULL"))
        execute("UPDATE $schema.client_instances SET push_signed_out_at = $SQL_START WHERE instance_id = '$existing'")
        // An older image's upsert of the same install (ClientInstanceRepository.upsert before V30) keeps the mark.
        execute(
            "INSERT INTO $schema.client_instances (id, user_id, kind, instance_id, version, custom, first_seen_at, last_seen_at) " +
                "VALUES ('${UUID.randomUUID()}', '$user', 'ANDROID', '$existing', '2.3.1', false, $SQL_END, $SQL_END) " +
                "ON CONFLICT (kind, instance_id) DO UPDATE SET version = EXCLUDED.version, last_seen_at = EXCLUDED.last_seen_at " +
                "WHERE client_instances.user_id = EXCLUDED.user_id",
        )
        assertEquals(
            1,
            count(
                "SELECT count(*) FROM $schema.client_instances WHERE instance_id = '$existing' AND version = '2.3.1' " +
                    "AND push_signed_out_at = $SQL_START",
            ),
        )
    }

    private fun instance(schema: String, user: UUID, instanceId: UUID) =
        "INSERT INTO $schema.client_instances (id, user_id, kind, instance_id, version, custom, first_seen_at, last_seen_at) " +
            "VALUES ('${UUID.randomUUID()}', '$user', 'ANDROID', '$instanceId', '2.3.0', false, $SQL_START, $SQL_START)"
}
