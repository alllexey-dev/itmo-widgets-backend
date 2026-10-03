package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `V6__drop_subject_link_saves.sql`. */
class V6DropSubjectLinkSavesTest : MigrationTestBase() {
    @Test
    fun `V6 drops saved links and keeps the links themselves`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = "5").migrate()
        val (owner, reader) = List(2) { UUID.randomUUID() }
        val linkId = UUID.randomUUID()
        connection().use { connection ->
            connection.createStatement().use { sql ->
                sql.executeUpdate("INSERT INTO $schema.users(id, isu) VALUES ('$owner', 950001), ('$reader', 950002)")
                sql.executeUpdate(
                    insertSql(
                        schema,
                        "subject_links",
                        mapOf(
                            "id" to "'$linkId'", "owner_id" to "'$owner'",
                            "subject_id" to "42", "subject_name" to "'Предмет'", "period_key" to "'2026-1'", "category" to "'MATERIALS'",
                            "url" to "'https://example.org/a'", "normalized_url" to "'https://example.org/a'", "visibility" to "'ALL'",
                            "created_at" to SQL_START, "updated_at" to SQL_START,
                        ),
                    ),
                )
                sql.executeUpdate(
                    insertSql(
                        schema,
                        "subject_link_saves",
                        mapOf("user_id" to "'$reader'", "link_id" to "'$linkId'", "created_at" to SQL_START),
                    ),
                )
            }
        }
        assertEquals(MigrationScripts.countAfter("5"), isolatedFlyway(schema).migrate().migrationsExecuted)
        connection().use { connection ->
            connection.createStatement().use { sql ->
                sql.executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname = '$schema' AND tablename = 'subject_link_saves'").use {
                    assertTrue(it.next())
                    assertEquals(0, it.getInt(1))
                }
                sql.executeQuery("SELECT count(*) FROM $schema.subject_links WHERE id = '$linkId'").use {
                    assertTrue(it.next())
                    assertEquals(1, it.getInt(1))
                }
            }
        }
    }
}
