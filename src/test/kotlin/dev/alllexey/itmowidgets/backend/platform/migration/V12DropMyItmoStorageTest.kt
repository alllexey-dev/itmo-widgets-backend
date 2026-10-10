package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** `V12__drop_my_itmo_storage.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V12DropMyItmoStorageTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("drop_my_itmo_storage")

    @Test
    fun `drops the legacy token table with its row and keeps every other table`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        execute(
            "INSERT INTO $schema.my_itmo_storage (id, refresh_token, refresh_token_expires_at, access_token_expires_at) " +
                "VALUES (1, 'synthetic-legacy-refresh', 1790000000123, 0)",
        )
        val credentials = count("SELECT count(*) FROM $schema.service_credentials")
        val before = tables(schema)

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        assertEquals(before - "my_itmo_storage", tables(schema))
        assertEquals(credentials, count("SELECT count(*) FROM $schema.service_credentials"))
    }

    private fun tables(schema: String): Set<String> = jdbc.queryForList(
        "SELECT tablename FROM pg_tables WHERE schemaname = ? AND tablename <> 'flyway_schema_history'",
        String::class.java,
        schema,
    ).toSet()
}
