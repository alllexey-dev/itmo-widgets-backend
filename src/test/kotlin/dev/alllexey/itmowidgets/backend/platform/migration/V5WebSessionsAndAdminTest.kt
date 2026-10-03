package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V5__web_sessions_and_admin.sql`. */
class V5WebSessionsAndAdminTest : MigrationTestBase() {
    @Test
    fun `V5 allows admins and constrains login challenges while one pending code stays unique`() {
        withConstraintSchema { schema, sql, owner, friend ->
            fun insert(table: String, values: Map<String, String>) = insertSql(schema, table, values)
            val role = mapOf("user_id" to "'$owner'", "role" to "'OWNER'", "granted_at" to SQL_START)
            assertSqlState(sql, "23514", insert("user_roles", role))
            assertSqlState(sql, "23514", insert("user_roles", role + ("role" to "'admin'")))
            for (valid in listOf(
                "'MODERATOR'",
                "'ADMIN'",
            )) {
                assertEquals(1, sql.executeUpdate(insert("user_roles", role + ("role" to valid))))
            }

            val challenge = mapOf(
                "code" to "'ABCD2345'",
                "poll_secret_hash" to "'${"a".repeat(64)}'",
                "status" to "'PENDING'",
                "user_agent" to "'Synthetic browser'",
                "client_ip" to "'203.0.113.7'",
                "created_at" to SQL_START,
                "expires_at" to SQL_END,
            )
            fun challenge(extra: Map<String, String> = emptyMap()) = challenge + ("id" to "'${UUID.randomUUID()}'") + extra
            val approval = mapOf("approved_by" to "'$owner'", "approved_at" to SQL_START)
            for (invalid in listOf(
                mapOf("status" to "'UNKNOWN'"),
                mapOf("status" to "'pending'"),
                mapOf("expires_at" to SQL_START),
                mapOf("status" to "'APPROVED'"),
                mapOf("status" to "'CLAIMED'"),
                mapOf("approved_by" to "'$owner'"),
            )) {
                assertSqlState(sql, "23514", insert("web_login_challenges", challenge(invalid)))
            }
            assertSqlState(
                sql,
                "23503",
                insert(
                    "web_login_challenges",
                    challenge(mapOf("status" to "'APPROVED'", "approved_by" to "'${UUID.randomUUID()}'", "approved_at" to SQL_START)),
                ),
            )
            assertEquals(1, sql.executeUpdate(insert("web_login_challenges", challenge())))
            assertSqlState(sql, "23505", insert("web_login_challenges", challenge()))
            // Used and expired codes do not reserve the code; a different pending code is independent.
            for (status in listOf("APPROVED", "CLAIMED")) {
                assertEquals(1, sql.executeUpdate(insert("web_login_challenges", challenge(approval + ("status" to "'$status'")))))
            }
            assertEquals(1, sql.executeUpdate(insert("web_login_challenges", challenge(mapOf("status" to "'EXPIRED'")))))
            assertEquals(1, sql.executeUpdate(insert("web_login_challenges", challenge(mapOf("code" to "'ZZZZ9999'")))))
            sql.executeUpdate("UPDATE $schema.web_login_challenges SET status = 'EXPIRED' WHERE code = 'ABCD2345' AND status = 'PENDING'")
            assertEquals(1, sql.executeUpdate(insert("web_login_challenges", challenge())))

            val setting = mapOf("key" to "'app.latest'", "value" to "'2.2'", "updated_at" to SQL_START)
            assertEquals(1, sql.executeUpdate(insert("app_settings", setting)))
            assertSqlState(sql, "23505", insert("app_settings", setting))
            assertSqlState(
                sql,
                "23503",
                insert(
                    "app_settings",
                    setting + mapOf("key" to "'app.minimum'", "updated_by" to "'${UUID.randomUUID()}'"),
                ),
            )
            val audit = mapOf(
                "id" to "'${UUID.randomUUID()}'",
                "actor_id" to "'$friend'",
                "action" to "'ROLE_GRANTED'",
                "target" to "'920001'",
                "created_at" to SQL_START,
            )
            assertSqlState(sql, "23503", insert("admin_audit", audit + ("actor_id" to "'${UUID.randomUUID()}'")))
            assertSqlState(sql, "23502", insert("admin_audit", audit + ("target" to "NULL")))
            assertEquals(1, sql.executeUpdate(insert("admin_audit", audit)))
        }
    }

    @Test
    fun `V5 keeps session token hashes unique and deleting a user removes its sessions and approvals`() {
        withConstraintSchema { schema, sql, owner, friend ->
            fun insert(table: String, values: Map<String, String>) = insertSql(schema, table, values)
            fun count(table: String, where: String): Int = sql.executeQuery("SELECT count(*) FROM $schema.$table WHERE $where").use {
                it.next()
                it.getInt(1)
            }
            val session = mapOf(
                "user_id" to "'$friend'",
                "token_hash" to "'${"b".repeat(64)}'",
                "created_at" to SQL_START,
                "last_seen_at" to SQL_START,
                "expires_at" to SQL_END,
            )
            fun session(extra: Map<String, String> = emptyMap()) = session + ("id" to "'${UUID.randomUUID()}'") + extra
            assertSqlState(sql, "23503", insert("web_sessions", session(mapOf("user_id" to "'${UUID.randomUUID()}'"))))
            assertSqlState(sql, "23514", insert("web_sessions", session(mapOf("expires_at" to SQL_START))))
            assertSqlState(sql, "23502", insert("web_sessions", session(mapOf("last_seen_at" to "NULL"))))
            assertEquals(1, sql.executeUpdate(insert("web_sessions", session())))
            assertSqlState(sql, "23505", insert("web_sessions", session(mapOf("user_id" to "'$owner'"))))
            assertEquals(1, sql.executeUpdate(insert("web_sessions", session(mapOf("token_hash" to "'${"c".repeat(64)}'")))))
            assertEquals(
                1,
                sql.executeUpdate(
                    insert(
                        "web_sessions",
                        session(
                            mapOf(
                                "user_id" to "'$owner'",
                                "token_hash" to "'${"d".repeat(64)}'",
                            ),
                        ),
                    ),
                ),
            )
            assertEquals(
                1,
                sql.executeUpdate(
                    insert(
                        "web_login_challenges",
                        mapOf(
                            "id" to "'${UUID.randomUUID()}'", "code" to "'ABCD2345'",
                            "poll_secret_hash" to "'${"a".repeat(64)}'", "status" to "'APPROVED'", "client_ip" to "'203.0.113.7'",
                            "created_at" to SQL_START, "expires_at" to SQL_END, "approved_by" to "'$friend'", "approved_at" to SQL_START,
                        ),
                    ),
                ),
            )

            assertEquals(1, sql.executeUpdate("DELETE FROM $schema.users WHERE id = '$friend'"))
            assertEquals(0, count("web_sessions", "user_id = '$friend'"))
            assertEquals(0, count("web_login_challenges", "approved_by = '$friend'"))
            assertEquals(1, count("web_sessions", "user_id = '$owner'"))
        }
    }
}
