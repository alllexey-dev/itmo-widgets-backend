package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `V10__teacher_summaries.sql`. */
class V10TeacherSummariesTest : MigrationTestBase() {
    @Test
    fun `V10 constrains summaries and adds the Gemini credential`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = "9").migrate()
        execute(
            "UPDATE $schema.service_credentials SET value = 'synthetic-cookie', status = 'OK', updated_source = 'SEED', " +
                "expires_at = $SQL_END WHERE key = 'ISU_KEYCLOAK_IDENTITY'",
        )
        val before = credentialStates(schema)
        assertEquals(1, isolatedFlyway(schema, target = "10").migrate().migrationsExecuted)
        val after = credentialStates(schema)
        assertEquals(before, after - "GEMINI_API_KEY")
        assertEquals(listOf<Any?>(false, "MISSING", null, null), after.getValue("GEMINI_API_KEY"))

        withConstraintSchema { constrained, sql, owner, _ ->
            fun insert(table: String, values: Map<String, String>) = insertSql(constrained, table, values)
            fun count(query: String): Int = sql.executeQuery(query).use {
                assertTrue(it.next())
                it.getInt(1)
            }
            assertSqlState(sql, "23514", "INSERT INTO $constrained.service_credentials (key, updated_at) VALUES ('OTHER', $SQL_START)")

            val content = mapOf(
                "content" to "'{}'",
                "content_hash" to "'${"a".repeat(64)}'",
                "content_count" to "3",
                "level" to "'MIXED'",
                "confidence" to "'LOW'",
                "model" to "'gemini-test'",
                "generated_at" to SQL_START,
            )
            val summary = mapOf(
                "teacher_isu" to "100123",
                "input_hash" to "'${"a".repeat(64)}'",
                "input_count" to "3",
                "updated_at" to SQL_START,
            ) + content
            for (invalid in listOf(
                mapOf("input_count" to "2"), mapOf("input_hash" to "NULL"), mapOf("level" to "NULL"),
                mapOf("content_count" to "2"), mapOf("level" to "'UNKNOWN'"), mapOf("confidence" to "'NONE'"),
                mapOf("attempts" to "-1"), mapOf("hidden_by" to "'$owner'"), mapOf("teacher_isu" to "99999"),
            )) {
                assertSqlState(sql, "23514", insert("teacher_summaries", summary + invalid))
            }
            assertSqlState(
                sql,
                "23503",
                insert(
                    "teacher_summaries",
                    summary + mapOf(
                        "hidden_at" to SQL_START,
                        "hidden_by" to "'${UUID.randomUUID()}'",
                    ),
                ),
            )
            assertEquals(
                1,
                sql.executeUpdate(
                    insert(
                        "teacher_summaries",
                        summary + mapOf(
                            "hidden_at" to SQL_START,
                            "hidden_by" to "'$owner'",
                        ),
                    ),
                ),
            )
            assertEquals(1, sql.executeUpdate(insert("teacher_summaries", mapOf("teacher_isu" to "100124", "updated_at" to SQL_START))))
            assertEquals(1, sql.executeUpdate("DELETE FROM $constrained.users WHERE id = '$owner'"))
            assertEquals(
                1,
                count(
                    "SELECT count(*) FROM $constrained.teacher_summaries " +
                        "WHERE teacher_isu = 100123 AND hidden_by IS NULL AND hidden_at IS NOT NULL",
                ),
            )

            assertEquals(1, count("SELECT count(*) FROM $constrained.teacher_summary_state WHERE id = 1 AND budget_used = 0"))
            assertSqlState(sql, "23514", "INSERT INTO $constrained.teacher_summary_state (id) VALUES (2)")
            assertSqlState(sql, "23514", "UPDATE $constrained.teacher_summary_state SET last_outcome = 'SKIPPED'")
            assertSqlState(sql, "23514", "UPDATE $constrained.teacher_summary_state SET budget_used = -1")
        }
    }

    /** Every credential row without its value: presence, status, source and expiry. */
    private fun credentialStates(schema: String): Map<String, List<Any?>> = connection().use { connection ->
        connection.createStatement().use { sql ->
            sql.executeQuery("SELECT key, value IS NOT NULL, status, updated_source, expires_at FROM $schema.service_credentials").use {
                buildMap {
                    while (it.next()) {
                        put(
                            it.getString(1),
                            listOf(
                                it.getBoolean(2),
                                it.getString(3),
                                it.getString(4),
                                it.getObject(5, OffsetDateTime::class.java)?.toInstant(),
                            ),
                        )
                    }
                }
            }
        }
    }
}
