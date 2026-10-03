package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `V2__friendships.sql`: single-row friendships converted from legacy friend requests. */
class V2FriendshipsTest : MigrationTestBase() {
    @Test
    fun `V2 converts legacy friend requests into single-row friendships and drops the old table`() {
        val schema = newSchemaName()
        val toV1 = isolatedFlyway(schema, target = "1")
        assertEquals(1, toV1.migrate().migrationsExecuted)
        val (anna, boris, vera, gleb) = List(4) { UUID.randomUUID() }
        val mutualFirst = UUID.randomUUID()
        val mutualSecond = UUID.randomUUID()
        val pending = UUID.randomUUID()
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO $schema.users(id, isu) VALUES ('$anna', 930001), ('$boris', 930002), ('$vera', 930003), ('$gleb', 930004)",
                )
                // Anna and Boris asked each other: one accepted friendship whose requester asked first.
                statement.executeUpdate(legacyRequest(schema, mutualSecond, boris, anna, "ACTIVE", SQL_END, SQL_END))
                statement.executeUpdate(legacyRequest(schema, mutualFirst, anna, boris, "ACTIVE", SQL_START, SQL_PREDICTED_START))
                // Vera asked Gleb and nobody answered: still pending in the same direction.
                statement.executeUpdate(legacyRequest(schema, pending, vera, gleb, "ACTIVE", SQL_START, SQL_START))
                // A cancelled request and a request cancelled by one side only carry no relationship.
                statement.executeUpdate(legacyRequest(schema, UUID.randomUUID(), gleb, anna, "CANCELLED", SQL_START, SQL_START))
                statement.executeUpdate(legacyRequest(schema, UUID.randomUUID(), boris, vera, "ACTIVE", SQL_END, SQL_END))
                statement.executeUpdate(legacyRequest(schema, UUID.randomUUID(), vera, boris, "CANCELLED", SQL_START, SQL_START))
            }
        }

        assertEquals(MigrationScripts.countAfter("1"), isolatedFlyway(schema).migrate().migrationsExecuted)

        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT id, requester_id, addressee_id, status, created_at, responded_at FROM $schema.friendships ORDER BY status, created_at",
                ).use {
                    assertTrue(it.next())
                    assertEquals(mutualFirst, it.getObject(1, UUID::class.java))
                    assertEquals(anna, it.getObject(2, UUID::class.java))
                    assertEquals(boris, it.getObject(3, UUID::class.java))
                    assertEquals("ACCEPTED", it.getString(4))
                    assertEquals(Instant.parse("2026-09-08T09:00:00Z"), it.getObject(5, OffsetDateTime::class.java).toInstant())
                    assertEquals(Instant.parse("2026-09-22T09:00:00Z"), it.getObject(6, OffsetDateTime::class.java).toInstant())
                    assertTrue(it.next())
                    assertEquals(pending, it.getObject(1, UUID::class.java))
                    assertEquals(vera, it.getObject(2, UUID::class.java))
                    assertEquals(gleb, it.getObject(3, UUID::class.java))
                    assertEquals("PENDING", it.getString(4))
                    assertEquals(null, it.getObject(6))
                    assertTrue(it.next())
                    assertEquals(boris, it.getObject(2, UUID::class.java))
                    assertEquals(vera, it.getObject(3, UUID::class.java))
                    assertEquals("PENDING", it.getString(4))
                    assertFalse(it.next())
                }
                statement.executeQuery(
                    "SELECT count(*) FROM pg_tables WHERE schemaname = '$schema' AND tablename = 'friend_requests'",
                ).use {
                    assertTrue(it.next())
                    assertEquals(0, it.getInt(1))
                }
            }
        }
    }

    @Test
    fun `friendship schema forbids reversed pairs unknown status and inconsistent response timestamps`() {
        withConstraintSchema { schema, statement, owner, friend ->
            val fields = mapOf(
                "id" to "'${UUID.randomUUID()}'",
                "requester_id" to "'$owner'",
                "addressee_id" to "'$friend'",
                "status" to "'PENDING'",
                "created_at" to SQL_START,
            )
            assertSqlState(statement, "23514", insertSql(schema, "friendships", fields + ("status" to "'BLOCKED'")))
            assertSqlState(statement, "23514", insertSql(schema, "friendships", fields + ("status" to "'ACCEPTED'")))
            assertSqlState(statement, "23514", insertSql(schema, "friendships", fields + ("responded_at" to SQL_START)))
            assertSqlState(
                statement,
                "23514",
                insertSql(
                    schema,
                    "friendships",
                    fields + mapOf(
                        "status" to "'ACCEPTED'",
                        "created_at" to SQL_END,
                        "responded_at" to SQL_START,
                    ),
                ),
            )
            assertSqlState(statement, "23503", insertSql(schema, "friendships", fields + ("addressee_id" to "'${UUID.randomUUID()}'")))
            assertEquals(1, statement.executeUpdate(insertSql(schema, "friendships", fields)))
            assertSqlState(
                statement,
                "23505",
                insertSql(
                    schema,
                    "friendships",
                    fields + mapOf(
                        "id" to "'${UUID.randomUUID()}'",
                        "requester_id" to "'$friend'",
                        "addressee_id" to "'$owner'",
                    ),
                ),
            )
            assertEquals(1, statement.executeUpdate("UPDATE $schema.friendships SET status = 'ACCEPTED', responded_at = $SQL_END"))
        }
    }

    @Test
    fun `database rejects self friendship`() {
        withConstraintSchema { schema, statement, owner, friend ->
            val request = mapOf(
                "id" to "'${UUID.randomUUID()}'",
                "requester_id" to "'$owner'",
                "addressee_id" to "'$friend'",
                "status" to "'PENDING'",
                "created_at" to SQL_START,
            )
            assertSqlState(statement, "23514", insertSql(schema, "friendships", request + ("addressee_id" to "'$owner'")))
            assertEquals(1, statement.executeUpdate(insertSql(schema, "friendships", request)))
        }
    }

    @Test
    fun `V1 and V2 give every data invariant check a stable explicit name`() {
        val expected = setOf(
            "ck_settings_auto_sign_limit", "ck_friendships_not_self", "ck_my_itmo_storage_singleton",
            "ck_sport_lessons_time_range", "ck_auto_sign_attempts", "ck_auto_sign_max_attempts",
            "ck_auto_sign_target_time_range", "ck_free_sign_attempts", "ck_free_sign_max_attempts",
            "ck_sport_update_duration", "ck_sport_update_received", "ck_sport_update_new",
            "ck_sport_update_updated", "ck_sport_update_skipped", "ck_sport_update_outcome",
            "ck_sport_update_error_category",
        )
        val schema = newSchemaName()
        isolatedFlyway(schema, target = "2").migrate()
        val names = jdbc.queryForList(
            """
            SELECT conname FROM pg_constraint
            WHERE connamespace = ?::regnamespace AND contype = 'c'
            """.trimIndent(),
            String::class.java,
            schema,
        ).toSet()
        assertEquals(expected, names.intersect(expected))
    }

    private fun legacyRequest(
        schema: String,
        id: UUID,
        from: UUID,
        to: UUID,
        status: String,
        createdAt: String,
        lastActivatedAt: String,
    ): String = insertSql(
        schema,
        "friend_requests",
        mapOf(
            "id" to "'$id'",
            "from_user_id" to "'$from'",
            "to_user_id" to "'$to'",
            "status" to "'$status'",
            "created_at" to createdAt,
            "last_activated_at" to lastActivatedAt,
        ),
    )
}
