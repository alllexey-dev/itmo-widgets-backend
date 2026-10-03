package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `V1__initial_postgresql_schema.sql`: users, settings, sport catalog, queues and the update journal. */
class V1InitialSchemaTest : MigrationTestBase() {
    @Test
    fun `schema enforces foreign keys privacy enum values and unique identities`() {
        val schema = newSchemaName()
        isolatedFlyway(schema).migrate()
        connection().use { connection ->
            connection.createStatement().use { statement ->
                val id = UUID.randomUUID()
                statement.execute("INSERT INTO $schema.users(id, isu) VALUES ('$id', 910001)")
                statement.execute("INSERT INTO $schema.user_settings(user_id) VALUES ('$id')")
                assertEquals(
                    "23505",
                    assertFailsWith<SQLException> {
                        statement.execute("INSERT INTO $schema.users(id, isu) VALUES ('${UUID.randomUUID()}', 910001)")
                    }.sqlState,
                )
                assertEquals(
                    "23514",
                    assertFailsWith<SQLException> {
                        statement.execute("UPDATE $schema.user_settings SET sport_visibility='UNKNOWN' WHERE user_id='$id'")
                    }.sqlState,
                )
                assertEquals(
                    "23505",
                    assertFailsWith<SQLException> {
                        statement.execute("INSERT INTO $schema.user_settings(user_id) VALUES ('$id')")
                    }.sqlState,
                )
                assertEquals(
                    "23503",
                    assertFailsWith<SQLException> {
                        statement.execute("INSERT INTO $schema.user_settings(user_id) VALUES ('${UUID.randomUUID()}')")
                    }.sqlState,
                )
                for (column in listOf("schedule_visibility", "sport_visibility", "friends_visibility")) {
                    assertEquals(
                        "23502",
                        assertFailsWith<SQLException> {
                            statement.execute("UPDATE $schema.user_settings SET $column=NULL WHERE user_id='$id'")
                        }.sqlState,
                    )
                    assertEquals(
                        "23514",
                        assertFailsWith<SQLException> {
                            statement.execute("UPDATE $schema.user_settings SET $column='UNKNOWN' WHERE user_id='$id'")
                        }.sqlState,
                    )
                }
                assertEquals(
                    "23503",
                    assertFailsWith<SQLException> {
                        statement.execute("INSERT INTO $schema.user_sport_lessons(user_id, lesson_id) VALUES ('$id', 999)")
                    }.sqlState,
                )
            }
        }
    }

    @Test
    fun `shared settings identity has only audiences and foreign key deletion never removes another user`() {
        val schema = newSchemaName()
        isolatedFlyway(schema).migrate()
        connection().use { connection ->
            connection.createStatement().use { statement ->
                val first = UUID.randomUUID()
                val second = UUID.randomUUID()
                val third = UUID.randomUUID()
                statement.execute("INSERT INTO $schema.users(id, isu) VALUES ('$first', 910001), ('$second', 910002), ('$third', 910003)")
                statement.execute("INSERT INTO $schema.user_settings(user_id) VALUES ('$first'), ('$second'), ('$third')")
                statement.executeQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema='$schema' AND table_name='user_settings'",
                ).use {
                    val columns = buildSet { while (it.next()) add(it.getString(1)) }
                    assertEquals(
                        setOf("user_id", "auto_sign_limit", "schedule_visibility", "sport_visibility", "friends_visibility"),
                        columns,
                    )
                }
                statement.executeQuery(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema='$schema' AND table_name='users' AND column_name='settings_id'",
                ).use {
                    assertTrue(it.next())
                    assertEquals(0, it.getInt(1))
                }
                statement.executeQuery(
                    "SELECT schedule_visibility, sport_visibility FROM $schema.user_settings WHERE user_id='$first'",
                ).use {
                    assertTrue(it.next())
                    assertEquals("FRIENDS", it.getString(1))
                    assertEquals("FRIENDS", it.getString(2))
                }
                statement.execute("DELETE FROM $schema.users WHERE id='$first'")
                statement.executeQuery("SELECT count(*) FROM $schema.user_settings WHERE user_id='$first'").use {
                    assertTrue(it.next())
                    assertEquals(0, it.getInt(1))
                }
                statement.execute("DELETE FROM $schema.user_settings WHERE user_id='$second'")
                statement.executeQuery("SELECT id FROM $schema.users").use {
                    val ids = buildSet { while (it.next()) add(it.getObject(1, UUID::class.java)) }
                    assertEquals(setOf(second, third), ids)
                }
                statement.executeQuery("SELECT user_id FROM $schema.user_settings").use {
                    assertTrue(it.next())
                    assertEquals(third, it.getObject(1, UUID::class.java))
                    assertFalse(it.next())
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `both queues reject negative attempts nonpositive limits and null counters`(automatic: Boolean) {
        withConstraintSchema { schema, statement, owner, _ ->
            val table = if (automatic) "sport_auto_sign_entries" else "sport_free_sign_entries"
            val fields = if (automatic) autoFields(owner) else freeFields(owner)
            assertSqlState(statement, "23514", insertSql(schema, table, fields + ("notification_attempts" to "-1")))
            for (invalidMaximum in listOf("-1", "0")) {
                assertSqlState(statement, "23514", insertSql(schema, table, fields + ("max_notification_attempts" to invalidMaximum)))
            }
            for (column in listOf("notification_attempts", "max_notification_attempts")) {
                assertSqlState(statement, "23502", insertSql(schema, table, fields + (column to "NULL")))
            }
            // Zero attempts and one allowed attempt are legitimate lower boundaries.
            assertEquals(1, statement.executeUpdate(insertSql(schema, table, fields)))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `lesson and frozen prediction require end strictly after start`(prediction: Boolean) {
        withConstraintSchema { schema, statement, owner, _ ->
            val table = if (prediction) "sport_auto_sign_entries" else "sport_lessons"
            val fields = if (prediction) autoFields(owner) else lessonFields(id = 2)
            val endColumn = if (prediction) "target_ends_at" else "ends_at"
            for (invalidEnd in listOf(SQL_START, "TIMESTAMPTZ '2026-09-08T08:59:59Z'")) {
                assertSqlState(statement, "23514", insertSql(schema, table, fields + (endColumn to invalidEnd)))
            }
            // The predicted window carries the same guarantee as the prototype it was derived from.
            if (prediction) {
                for (invalidEnd in listOf(SQL_PREDICTED_START, "TIMESTAMPTZ '2026-09-22T08:59:59Z'")) {
                    assertSqlState(statement, "23514", insertSql(schema, table, fields + ("predicted_ends_at" to invalidEnd)))
                }
            }
            assertEquals(1, statement.executeUpdate(insertSql(schema, table, fields)))
        }
    }

    @Test
    fun `required frozen prediction columns reject null even on cancelled entries`() {
        withConstraintSchema { schema, statement, owner, _ ->
            val fields = autoFields(owner)
            val snapshotColumns = listOf(
                "target_section_id", "target_section_name", "target_section_level", "target_lesson_level",
                "target_type_id", "target_time_slot_id", "target_teacher_isu",
                "target_teacher_name", "target_room_id", "target_room_name", "target_starts_at", "target_ends_at",
                "predicted_starts_at", "predicted_ends_at",
            )
            for (column in snapshotColumns) {
                assertSqlState(statement, "23502", insertSql(schema, "sport_auto_sign_entries", fields + (column to "NULL")))
            }
            assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_auto_sign_entries", fields)))
        }
    }

    @Test
    fun `raw venue ids do not require filter rows and online snapshots preserve null`() {
        withConstraintSchema { schema, statement, owner, _ ->
            assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_lessons", lessonFields(id = 2) + ("building_id" to "999999"))))
            assertEquals(
                1,
                statement.executeUpdate(
                    insertSql(
                        schema,
                        "sport_lessons",
                        lessonFields(id = 3) + mapOf("building_id" to "NULL", "room_id" to "-1"),
                    ),
                ),
            )
            // An unusable venue yields no key at all, so the column has to accept NULL.
            assertEquals(
                1,
                statement.executeUpdate(
                    insertSql(
                        schema,
                        "sport_auto_sign_entries",
                        autoFields(owner) + mapOf("target_building_id" to "NULL", "target_room_id" to "-1", "match_key" to "NULL"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `catalog last seen timestamp is mandatory and accepts deterministic historical values`() {
        withConstraintSchema { schema, statement, _, _ ->
            val fields = lessonFields(id = 2)
            assertSqlState(statement, "23502", insertSql(schema, "sport_lessons", fields + ("last_seen_at" to "NULL")))
            assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_lessons", fields)))
        }
    }

    @Test
    fun `update journal rejects every negative counter and duration while allowing zeros`() {
        withConstraintSchema { schema, statement, _, _ ->
            val fields = logFields()
            for (column in listOf("duration_millis", "received_lessons", "new_lessons_added", "updated_lessons", "skipped_lessons")) {
                assertSqlState(statement, "23514", insertSql(schema, "sport_update_logs", fields + (column to "-1")))
            }
            assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_update_logs", fields)))
        }
    }

    @Test
    fun `update journal requires timestamp outcome duration and every counter`() {
        withConstraintSchema { schema, statement, _, _ ->
            val fields = logFields()
            for (column in listOf(
                "update_timestamp",
                "outcome",
                "duration_millis",
                "received_lessons",
                "new_lessons_added",
                "updated_lessons",
                "skipped_lessons",
            )) {
                assertSqlState(statement, "23502", insertSql(schema, "sport_update_logs", fields + (column to "NULL")))
            }
            assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_update_logs", fields)))
        }
    }

    @Test
    fun `update journal constrains outcome and error categories without requiring an error`() {
        withConstraintSchema { schema, statement, _, _ ->
            val fields = logFields()
            for (column in listOf("outcome", "error_category")) {
                assertSqlState(statement, "23514", insertSql(schema, "sport_update_logs", fields + (column to "'UNKNOWN'")))
            }
            for (outcome in listOf("SUCCESS", "PARTIAL", "FAILED")) {
                assertEquals(1, statement.executeUpdate(insertSql(schema, "sport_update_logs", fields + ("outcome" to "'$outcome'"))))
            }
            for (category in listOf("AUTH", "NETWORK", "HTTP", "MAPPING", "PERSISTENCE", "INTERNAL")) {
                assertEquals(
                    1,
                    statement.executeUpdate(
                        insertSql(
                            schema,
                            "sport_update_logs",
                            fields + mapOf(
                                "outcome" to "'FAILED'",
                                "error_category" to "'$category'",
                            ),
                        ),
                    ),
                )
            }
            statement.executeQuery("SELECT count(*) FROM $schema.sport_update_logs WHERE error_category IS NULL").use {
                assertTrue(it.next())
                assertEquals(3, it.getInt(1))
            }
        }
    }

    @Test
    fun `V1 rejects a negative quota and a second token storage row`() {
        // my_itmo_storage is legacy since V8, so the singleton check runs on the V1 schema.
        withConstraintSchema(target = "1") { schema, statement, owner, _ ->
            val settings = mapOf("user_id" to "'$owner'", "auto_sign_limit" to "0")
            assertSqlState(statement, "23514", insertSql(schema, "user_settings", settings + ("auto_sign_limit" to "-1")))
            assertSqlState(statement, "23502", insertSql(schema, "user_settings", settings + ("auto_sign_limit" to "NULL")))
            assertEquals(1, statement.executeUpdate(insertSql(schema, "user_settings", settings)))

            val storage = mapOf("id" to "1", "refresh_token_expires_at" to "0", "access_token_expires_at" to "0")
            for (invalidId in listOf("-1", "0", "2")) {
                assertSqlState(statement, "23514", insertSql(schema, "my_itmo_storage", storage + ("id" to invalidId)))
            }
            assertEquals(1, statement.executeUpdate(insertSql(schema, "my_itmo_storage", storage)))
        }
    }
}
