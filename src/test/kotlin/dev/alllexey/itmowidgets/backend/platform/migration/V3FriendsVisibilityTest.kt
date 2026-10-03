package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `V3__friends_visibility.sql`. */
class V3FriendsVisibilityTest : MigrationTestBase() {
    @Test
    fun `V3 opens friends by default without changing existing schedule sport or quota`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = "2").migrate()
        val id = UUID.randomUUID()
        connection().use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("INSERT INTO $schema.users(id, isu) VALUES ('$id', 940001)")
                sql.execute(
                    "INSERT INTO $schema.user_settings(user_id, schedule_visibility, sport_visibility, auto_sign_limit) VALUES ('$id', 'NOBODY', 'FRIENDS', 7)",
                )
            }
        }
        assertEquals(MigrationScripts.countAfter("2"), isolatedFlyway(schema).migrate().migrationsExecuted)
        connection().use { connection ->
            connection.createStatement().use { sql ->
                sql.executeQuery(
                    "SELECT friends_visibility, schedule_visibility, sport_visibility, auto_sign_limit FROM $schema.user_settings",
                ).use {
                    assertTrue(it.next())
                    assertEquals("ALL", it.getString(1))
                    assertEquals("NOBODY", it.getString(2))
                    assertEquals("FRIENDS", it.getString(3))
                    assertEquals(7, it.getInt(4))
                }
                for (value in listOf("'INVALID'", "NULL")) {
                    assertFailsWith<SQLException> { sql.execute("UPDATE $schema.user_settings SET friends_visibility=$value") }
                }
            }
        }
    }
}
