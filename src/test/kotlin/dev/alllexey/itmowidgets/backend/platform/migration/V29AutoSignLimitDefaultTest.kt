package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V29__auto_sign_limit_default.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V29AutoSignLimitDefaultTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("auto_sign_limit_default")

    @Test
    fun `existing rows keep their limit and new rows get the global default`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val existing = UUID.randomUUID()
        val created = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu) VALUES ('$existing', 963301), ('$created', 963302)")
        execute("INSERT INTO $schema.user_settings(user_id, auto_sign_limit) VALUES ('$existing', 7)")

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        // The way Backend creates a settings row (UserRepository.insertSettingsIgnore).
        execute("INSERT INTO $schema.user_settings(user_id) VALUES ('$created')")
        assertEquals(1, count("SELECT count(*) FROM $schema.user_settings WHERE user_id = '$existing' AND auto_sign_limit = 7"))
        assertEquals(1, count("SELECT count(*) FROM $schema.user_settings WHERE user_id = '$created' AND auto_sign_limit = 10"))
    }

    @Test
    fun `the retired column keeps its constraints`() {
        withConstraintSchema(target = version) { schema, sql, owner, _ ->
            val settings = mapOf("user_id" to "'$owner'")
            assertSqlState(sql, "23514", insertSql(schema, "user_settings", settings + ("auto_sign_limit" to "-1")))
            assertSqlState(sql, "23502", insertSql(schema, "user_settings", settings + ("auto_sign_limit" to "NULL")))
        }
    }
}
