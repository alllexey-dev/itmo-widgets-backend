package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** `V28__sheet_rules.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V28SheetRulesTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("sheet_rules")

    @Test
    fun `the migration adds the rules and the state row`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)
        assertEquals(1, count("SELECT count(*) FROM $schema.sheet_rule_state WHERE id = 1 AND running_since IS NULL AND budget_used = 0"))
        assertEquals(0, count("SELECT count(*) FROM $schema.sheet_rules"))
    }

    @Test
    fun `a rule needs its verdict and only a live status keeps it`() {
        withConstraintSchema { schema, sql, _, _ ->
            var id = 0
            fun row(values: Map<String, String>) = insertSql(
                schema,
                "sheet_rules",
                mapOf("sheet_id" to "'sheet-${id++}'", "status" to "'PENDING'", "updated_at" to SQL_START) + values,
            )
            val verdict = mapOf("fingerprint" to "'${"a".repeat(64)}'", "model" to "'claude-sonnet-5-5'", "analyzed_at" to SQL_START)
            val rule = verdict + ("rule" to "'{\"schema_version\":1}'")
            for (invalid in listOf(
                mapOf("sheet_id" to "'bad id'"),
                mapOf("status" to "'DONE'"),
                mapOf("status" to "'READY'") + verdict,
                mapOf("status" to "'INSUFFICIENT'"),
                mapOf("status" to "'FAILED'") + rule,
                mapOf("status" to "'INSUFFICIENT'") + rule,
                mapOf("rule" to "'[1]'") + verdict,
                mapOf("rule" to "'{}'"),
                verdict - "model",
                verdict + ("fingerprint" to "'ABC'"),
                mapOf("attempts" to "-1"),
            )) {
                assertSqlState(sql, "23514", row(invalid))
            }
            for (valid in listOf(
                emptyMap(),
                mapOf("status" to "'READY'") + rule,
                mapOf("status" to "'RUNNING'") + rule,
                mapOf("status" to "'INSUFFICIENT'") + verdict,
                mapOf("status" to "'PENDING'") + verdict,
                mapOf("status" to "'FAILED'", "attempts" to "3", "last_error" to "'closed'"),
            )) {
                assertEquals(1, sql.executeUpdate(row(valid)))
            }
            assertSqlState(sql, "23514", "INSERT INTO $schema.sheet_rule_state (id) VALUES (2)")
            assertSqlState(sql, "23514", "UPDATE $schema.sheet_rule_state SET budget_used = -1")
        }
    }
}
