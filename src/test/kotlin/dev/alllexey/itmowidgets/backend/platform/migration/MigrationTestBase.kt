package dev.alllexey.itmowidgets.backend.platform.migration

import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlTestDatabase
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal const val SQL_START = "TIMESTAMPTZ '2026-09-08T09:00:00Z'"
internal const val SQL_END = "TIMESTAMPTZ '2026-09-08T10:00:00Z'"
internal const val SQL_PREDICTED_START = "TIMESTAMPTZ '2026-09-22T09:00:00Z'"
internal const val SQL_PREDICTED_END = "TIMESTAMPTZ '2026-09-22T10:00:00Z'"

/**
 * The migration suites, one class per script plus [FlywaySchemaTest]. They share one Spring context (the
 * Flyway-migrated `public` schema) and run each scenario in a fresh schema of the same container.
 */
abstract class MigrationTestBase : PostgreSqlRepositoryTest() {
    @Autowired protected lateinit var flyway: Flyway

    @Autowired protected lateinit var jdbc: JdbcTemplate

    @Autowired protected lateinit var em: TestEntityManager

    // Random schemas exist only inside this JVM's disposable container, never in an external DB.
    protected fun newSchemaName(): String = "migration_" + UUID.randomUUID().toString().replace("-", "")

    /** The application's Flyway settings on [schema]; [target] stops at that version, null runs every script. */
    protected fun isolatedFlyway(schema: String, target: String? = null): Flyway = Flyway
        .configure()
        .configuration(flyway.configuration)
        .schemas(schema)
        .defaultSchema(schema)
        .apply { if (target != null) target(target) }
        .load()

    protected fun connection(): Connection = PostgreSqlTestDatabase.container.let {
        DriverManager.getConnection(it.jdbcUrl, it.username, it.password)
    }

    protected fun execute(sql: String) = connection().use { connection -> connection.createStatement().use { it.executeUpdate(sql) } }

    protected fun count(sql: String): Long = connection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use {
                assertTrue(it.next())
                it.getLong(1)
            }
        }
    }

    /**
     * A fresh schema migrated to [target] (every script when null) with two users and one sport lesson, and an
     * auto-commit statement on it, so one deliberately rejected INSERT does not poison later assertions.
     */
    protected fun withConstraintSchema(target: String? = null, action: (String, Statement, UUID, UUID) -> Unit) {
        val schema = newSchemaName()
        isolatedFlyway(schema, target).migrate()
        connection().use { connection ->
            assertTrue(connection.autoCommit)
            connection.createStatement().use { statement ->
                val owner = UUID.randomUUID()
                val friend = UUID.randomUUID()
                statement.executeUpdate("INSERT INTO $schema.users(id, isu) VALUES ('$owner', 920001), ('$friend', 920002)")
                statement.executeUpdate("INSERT INTO $schema.sport_sections(id, name) VALUES (1, 'Synthetic section')")
                statement.executeUpdate("INSERT INTO $schema.sport_buildings(id, name) VALUES (1, 'Synthetic building')")
                statement.executeUpdate("INSERT INTO $schema.sport_teachers(isu, name) VALUES (1, 'Synthetic teacher')")
                statement.executeUpdate("INSERT INTO $schema.sport_time_slots(id, time_start, time_end) VALUES (1, '12:00', '13:00')")
                statement.executeUpdate(insertSql(schema, "sport_lessons", lessonFields(id = 1)))
                action(schema, statement, owner, friend)
            }
        }
    }

    protected fun lessonFields(id: Long): Map<String, String> = mapOf(
        "id" to id.toString(), "section_id" to "1", "section_level" to "1", "lesson_level" to "1",
        "type_id" to "1", "section_name" to "'Synthetic section'", "time_slot_id" to "1",
        "building_id" to "1", "teacher_isu" to "1", "room_id" to "1", "room_name" to "'Synthetic room'",
        "starts_at" to SQL_START, "ends_at" to SQL_END, "last_seen_at" to SQL_START,
    )

    protected fun autoFields(owner: UUID): Map<String, String> = mapOf(
        "user_id" to "'$owner'", "prototype_lesson_id" to "1", "target_section_id" to "1",
        "target_section_name" to "'Synthetic section'", "target_section_level" to "1", "target_lesson_level" to "1",
        "target_type_id" to "1", "target_time_slot_id" to "1", "target_building_id" to "1",
        "target_teacher_isu" to "1", "target_teacher_name" to "'Synthetic teacher'",
        "target_room_id" to "1", "target_room_name" to "'Synthetic room'",
        "target_starts_at" to SQL_START, "target_ends_at" to SQL_END,
        "predicted_starts_at" to SQL_PREDICTED_START, "predicted_ends_at" to SQL_PREDICTED_END,
        "match_key" to "'1|1|b1|1|1|1|1|1|0|0'",
        "is_cancelled" to "true", "notification_attempts" to "0", "max_notification_attempts" to "1",
    )

    protected fun freeFields(owner: UUID): Map<String, String> = mapOf(
        "user_id" to "'$owner'",
        "lesson_id" to "1",
        "force_sign" to "false",
        "is_cancelled" to "true",
        "notification_attempts" to "0",
        "max_notification_attempts" to "1",
    )

    protected fun logFields(): Map<String, String> = mapOf(
        "update_timestamp" to SQL_START,
        "outcome" to "'SUCCESS'",
        "duration_millis" to "0",
        "received_lessons" to "0",
        "new_lessons_added" to "0",
        "updated_lessons" to "0",
        "skipped_lessons" to "0",
        "error_category" to "NULL",
    )

    // Only literal synthetic test values and fixed identifiers enter these SQL expressions.
    protected fun insertSql(schema: String, table: String, fields: Map<String, String>): String =
        "INSERT INTO $schema.$table (${fields.keys.joinToString()}) VALUES (${fields.values.joinToString()})"

    protected fun assertSqlState(statement: Statement, expected: String, sql: String) {
        assertEquals(expected, assertFailsWith<SQLException> { statement.executeUpdate(sql) }.sqlState, sql)
    }
}
