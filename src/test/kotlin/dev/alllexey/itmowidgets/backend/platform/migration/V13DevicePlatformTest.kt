package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V13__device_platform.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V13DevicePlatformTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("device_platform")

    @Test
    fun `existing devices become Android FCM devices with alerts without rewriting the table`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val user = UUID.randomUUID()
        val device = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu) VALUES ('$user', 963101)")
        execute(
            "INSERT INTO $schema.devices(id, user_id, fcm_token, device_name, last_login, app_version) " +
                "VALUES ('$device', '$user', 'synthetic-token-963101', 'Synthetic phone', $SQL_START, '2.2')",
        )
        val storage = count("SELECT pg_relation_filenode('$schema.devices')::bigint")

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        // Non-volatile defaults are a catalog change: the table keeps its file, nothing is rewritten.
        assertEquals(storage, count("SELECT pg_relation_filenode('$schema.devices')::bigint"))
        assertEquals(
            1,
            count(
                "SELECT count(*) FROM $schema.devices WHERE id = '$device' AND fcm_token = 'synthetic-token-963101' " +
                    "AND platform = 'ANDROID' AND alerts_allowed AND push_provider = 'FCM' AND created_at IS NOT NULL " +
                    "AND app_version = '2.2' AND last_login = $SQL_START",
            ),
        )
    }

    @Test
    fun `a row inserted the way 1_7_0 inserts gets the defaults and the platform is a known one`() {
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
            assertSqlState(sql, "23514", insert("synthetic-web", mapOf("platform" to "'WEB'")))
            assertSqlState(sql, "23514", insert("synthetic-lowercase", mapOf("platform" to "'ios'")))
            for ((index, column) in listOf("platform", "alerts_allowed", "push_provider", "created_at").withIndex()) {
                assertSqlState(sql, "23502", insert("synthetic-null-$index", mapOf(column to "NULL")))
            }

            assertEquals(1, sql.executeUpdate(insert("synthetic-legacy", emptyMap())))
            val ios = mapOf("platform" to "'IOS'", "alerts_allowed" to "false", "app_version" to "'2.3.0'")
            assertEquals(1, sql.executeUpdate(insert("synthetic-ios", ios)))
            assertEquals(
                1,
                count(
                    "SELECT count(*) FROM $schema.devices WHERE fcm_token = 'synthetic-legacy' AND platform = 'ANDROID' " +
                        "AND alerts_allowed AND push_provider = 'FCM' AND created_at IS NOT NULL",
                ),
            )
        }
    }
}
