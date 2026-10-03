package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `V8__service_credentials.sql`. */
class V8ServiceCredentialsTest : MigrationTestBase() {
    @Test
    fun `V8 copies the MyITMO credential into service credentials and keeps the old table`() {
        // Later scripts may drop the old table, so every schema here stops at V8.
        val copied = schemaBeforeV8()
        val legacyValues = "'synthetic-refresh', 1790000000123, 'synthetic-access', 0, 'synthetic-id'"
        val legacyRow = listOf("synthetic-refresh", 1790000000123L, "synthetic-access", 0L, "synthetic-id")
        execute(
            "INSERT INTO $copied.my_itmo_storage (id, refresh_token, refresh_token_expires_at, access_token, " +
                "access_token_expires_at, id_token) VALUES (1, $legacyValues)",
        )
        val columnsBefore = legacyColumns(copied)
        assertEquals(1, isolatedFlyway(copied, target = "8").migrate().migrationsExecuted)

        val rows = credentialRows(copied)
        assertEquals(
            CredentialRow("synthetic-refresh", Instant.ofEpochMilli(1790000000123), "UNKNOWN", "MIGRATION"),
            rows.getValue("MY_ITMO_REFRESH_TOKEN"),
        )
        assertEquals(CredentialRow("synthetic-access", null, "UNKNOWN", "MIGRATION"), rows.getValue("MY_ITMO_ACCESS_TOKEN"))
        assertEquals(CredentialRow("synthetic-id", null, "UNKNOWN", "MIGRATION"), rows.getValue("MY_ITMO_ID_TOKEN"))
        assertEquals(CredentialRow(null, null, "MISSING", null), rows.getValue("ISU_KEYCLOAK_IDENTITY"))
        assertEquals(1L, count("SELECT count(*) FROM pg_tables WHERE schemaname = '$copied' AND tablename = 'my_itmo_storage'"))
        assertEquals(listOf(listOf<Any?>(1L) + legacyRow), legacyRows(copied))
        assertEquals(columnsBefore, legacyColumns(copied))

        val blank = schemaBeforeV8()
        execute(
            "INSERT INTO $blank.my_itmo_storage (id, refresh_token, refresh_token_expires_at, access_token_expires_at) " +
                "VALUES (1, '  ', 1790000000123, 0)",
        )
        assertEquals(1, isolatedFlyway(blank, target = "8").migrate().migrationsExecuted)
        assertEquals(ALL_MISSING, credentialRows(blank))
        assertEquals(listOf(listOf<Any?>(1L, "  ", 1790000000123L, null, 0L, null)), legacyRows(blank))

        val empty = schemaBeforeV8()
        assertEquals(1, isolatedFlyway(empty, target = "8").migrate().migrationsExecuted)
        assertEquals(ALL_MISSING, credentialRows(empty))
        assertEquals(1L, count("SELECT count(*) FROM pg_tables WHERE schemaname = '$empty' AND tablename = 'my_itmo_storage'"))
        assertEquals(emptyList(), legacyRows(empty))
    }

    @Test
    fun `V8 constrains service credentials`() {
        withConstraintSchema { schema, sql, owner, _ ->
            val table = "$schema.service_credentials"
            assertSqlState(sql, "23514", "INSERT INTO $table (key, updated_at) VALUES ('OTHER_SECRET', $SQL_START)")
            assertSqlState(sql, "23514", "UPDATE $table SET status = 'OK' WHERE key = 'ISU_KEYCLOAK_IDENTITY'")
            assertSqlState(sql, "23514", "UPDATE $table SET value = 'synthetic-cookie' WHERE key = 'ISU_KEYCLOAK_IDENTITY'")
            assertSqlState(
                sql,
                "23514",
                "UPDATE $table SET value = 'synthetic-cookie', status = 'UNKNOWN', " +
                    "updated_source = 'SEED', updated_by = '$owner' WHERE key = 'ISU_KEYCLOAK_IDENTITY'",
            )
            assertSqlState(sql, "23505", "INSERT INTO $table (key, updated_at) VALUES ('ISU_KEYCLOAK_IDENTITY', $SQL_START)")
            assertEquals(
                1,
                sql.executeUpdate(
                    "UPDATE $table SET value = 'synthetic-cookie', status = 'UNKNOWN', " +
                        "updated_source = 'ADMIN', updated_by = '$owner' WHERE key = 'ISU_KEYCLOAK_IDENTITY'",
                ),
            )

            assertEquals(1, sql.executeUpdate("DELETE FROM $schema.users WHERE id = '$owner'"))
            sql.executeQuery("SELECT value, updated_by, updated_source FROM $table WHERE key = 'ISU_KEYCLOAK_IDENTITY'").use {
                assertTrue(it.next())
                assertEquals("synthetic-cookie", it.getString(1))
                assertEquals(null, it.getObject(2))
                assertEquals("ADMIN", it.getString(3))
            }
        }
    }

    private data class CredentialRow(val value: String?, val expiresAt: Instant?, val status: String, val source: String?)

    private fun schemaBeforeV8(): String = newSchemaName().also { schema ->
        isolatedFlyway(schema, target = "7").migrate()
    }

    private fun credentialRows(schema: String): Map<String, CredentialRow> = connection().use { connection ->
        connection.createStatement().use { sql ->
            sql.executeQuery("SELECT key, value, expires_at, status, updated_source FROM $schema.service_credentials").use {
                buildMap {
                    while (it.next()) {
                        put(
                            it.getString(1),
                            CredentialRow(
                                it.getString(2),
                                it.getObject(3, OffsetDateTime::class.java)?.toInstant(),
                                it.getString(4),
                                it.getString(5),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun legacyRows(schema: String): List<List<Any?>> = connection().use { connection ->
        connection.createStatement().use { sql ->
            sql.executeQuery(
                "SELECT id, refresh_token, refresh_token_expires_at, access_token, access_token_expires_at, id_token " +
                    "FROM $schema.my_itmo_storage ORDER BY id",
            ).use {
                buildList { while (it.next()) add((1..6).map { column -> it.getObject(column) }) }
            }
        }
    }

    private fun legacyColumns(schema: String): List<String> = jdbc.queryForList(
        "SELECT column_name || ' ' || data_type || ' ' || is_nullable FROM information_schema.columns " +
            "WHERE table_schema = ? AND table_name = 'my_itmo_storage' ORDER BY ordinal_position",
        String::class.java,
        schema,
    )

    companion object {
        private val ALL_MISSING = listOf(
            "MY_ITMO_REFRESH_TOKEN",
            "MY_ITMO_ACCESS_TOKEN",
            "MY_ITMO_ID_TOKEN",
            "ISU_KEYCLOAK_IDENTITY",
        )
            .associateWith { CredentialRow(null, null, "MISSING", null) }
    }
}
