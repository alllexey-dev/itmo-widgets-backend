package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V11__device_app_version.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V11DeviceAppVersionTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("device_app_version")

    @Test
    fun `adds empty build columns to a 1_7_0 schema without rewriting devices`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = "10").migrate()
        val user = UUID.randomUUID()
        val device = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu) VALUES ('$user', 963001)")
        execute(
            "INSERT INTO $schema.devices(id, user_id, fcm_token, device_name, last_login) " +
                "VALUES ('$device', '$user', 'synthetic-token-963001', 'Synthetic phone', $SQL_START)",
        )

        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val storage = count("SELECT pg_relation_filenode('$schema.devices')::bigint")
        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        // Nullable columns without defaults are a catalog change: the table keeps its file, nothing is rewritten.
        assertEquals(storage, count("SELECT pg_relation_filenode('$schema.devices')::bigint"))

        assertEquals(
            1,
            count(
                "SELECT count(*) FROM $schema.devices WHERE id = '$device' AND fcm_token = 'synthetic-token-963001' " +
                    "AND last_login = $SQL_START AND app_version IS NULL AND app_build IS NULL AND app_platform IS NULL " +
                    "AND app_distribution IS NULL AND app_version_seen_at IS NULL",
            ),
        )
        assertEquals(
            listOf(
                "app_build:integer",
                "app_distribution:character varying:16",
                "app_platform:character varying:16",
                "app_version:character varying:32",
                "app_version_seen_at:timestamp with time zone",
            ),
            columns(schema),
        )
    }

    @Test
    fun `a report is stored whole with a known platform and a positive build`() {
        withConstraintSchema(target = version) { schema, sql, owner, _ ->
            fun insert(token: String, values: Map<String, String>) = insertSql(
                schema,
                "devices",
                mapOf(
                    "id" to "'${UUID.randomUUID()}'",
                    "user_id" to "'$owner'",
                    "fcm_token" to "'$token'",
                    "device_name" to "'Synthetic phone'",
                    "last_login" to SQL_START,
                ) + values,
            )
            val report = mapOf(
                "app_version" to "'2.3.0-beta.1'",
                "app_build" to "20291",
                "app_platform" to "'ANDROID'",
                "app_distribution" to "'github'",
                "app_version_seen_at" to SQL_END,
            )
            for ((index, invalid) in listOf(
                mapOf("app_platform" to "'WEB'"),
                mapOf("app_platform" to "'android'"),
                mapOf("app_build" to "0"),
                mapOf("app_build" to "NULL"),
                mapOf("app_platform" to "NULL"),
                mapOf("app_distribution" to "NULL"),
                mapOf("app_version" to "NULL"),
                mapOf("app_version_seen_at" to "NULL"),
            ).withIndex()) {
                assertSqlState(sql, "23514", insert("synthetic-invalid-$index", report + invalid))
            }
            assertSqlState(sql, "22001", insert("synthetic-long", report + mapOf("app_version" to "'${"1".repeat(33)}'")))

            assertEquals(1, sql.executeUpdate(insert("synthetic-reported", report)))
            val ios = report + mapOf("app_platform" to "'IOS'", "app_distribution" to "'appstore'")
            assertEquals(1, sql.executeUpdate(insert("synthetic-ios", ios)))
            assertEquals(1, sql.executeUpdate(insert("synthetic-legacy", emptyMap())))
            // A registration may name a version without reporting a build (the ledger's device_platform migration).
            assertEquals(1, sql.executeUpdate(insert("synthetic-registered", mapOf("app_version" to "'2.3.0'"))))
        }
    }

    private fun columns(schema: String): List<String> = connection().use { connection ->
        connection.createStatement().use { sql ->
            sql.executeQuery(
                "SELECT column_name, data_type, character_maximum_length FROM information_schema.columns " +
                    "WHERE table_schema = '$schema' AND table_name = 'devices' AND column_name LIKE 'app\\_%' ORDER BY column_name",
            ).use {
                buildList {
                    while (it.next()) add(listOfNotNull(it.getString(1), it.getString(2), it.getObject(3)?.toString()).joinToString(":"))
                }
            }
        }
    }
}
