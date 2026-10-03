package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `V4__subject_links.sql`: roles, moderation and subject links. */
class V4SubjectLinksTest : MigrationTestBase() {
    @Test
    fun `V4 rejects invalid moderation invariants and lets the policy only approve without a moderator`() {
        withConstraintSchema { schema, sql, owner, friend ->
            fun insert(table: String, values: Map<String, String>) = insertSql(schema, table, values)
            val targetId = UUID.randomUUID()
            val role = mapOf("user_id" to "'$owner'", "role" to "'OWNER'", "granted_at" to SQL_START)
            assertSqlState(sql, "23514", insert("user_roles", role))
            assertEquals(1, sql.executeUpdate(insert("user_roles", role + ("role" to "'MODERATOR'"))))
            val caseId = UUID.randomUUID()
            val case = mapOf(
                "id" to "'$caseId'",
                "target_type" to "'SUBJECT_RESOURCE'",
                "target_id" to "'$targetId'",
                "status" to "'OPEN'",
                "reason" to "'SUBMISSION'",
                "opened_at" to SQL_START,
            )
            for ((column, invalid) in mapOf(
                "target_type" to "'UNKNOWN'",
                "status" to "'UNKNOWN'",
                "reason" to "'UNKNOWN'",
                "resolved_at" to SQL_END,
            )) {
                assertSqlState(sql, "23514", insert("moderation_cases", case + (column to invalid)))
            }
            assertSqlState(sql, "23514", insert("moderation_cases", case + ("status" to "'RESOLVED'")))
            assertEquals(1, sql.executeUpdate(insert("moderation_cases", case)))
            assertSqlState(sql, "23505", insert("moderation_cases", case + ("id" to "'${UUID.randomUUID()}'")))
            repeat(2) {
                assertEquals(
                    1,
                    sql.executeUpdate(
                        insert(
                            "moderation_cases",
                            case + mapOf("id" to "'${UUID.randomUUID()}'", "status" to "'RESOLVED'", "resolved_at" to SQL_END),
                        ),
                    ),
                )
            }
            val decisionId = UUID.randomUUID()
            val decision = mapOf(
                "id" to "'$decisionId'",
                "case_id" to "'$caseId'",
                "moderator_id" to "'$owner'",
                "action" to "'APPROVE'",
                "created_at" to SQL_START,
            )
            for (invalid in listOf(
                mapOf("action" to "'UNKNOWN'"), mapOf("action" to "'RESTRICT_USER'"),
                mapOf("restriction_capability" to "'ALL'"), mapOf("restriction_days" to "1"),
                mapOf("action" to "'RESTRICT_USER'", "restriction_capability" to "'ALL'", "restriction_days" to "0"),
                mapOf("action" to "'RESTRICT_USER'", "restriction_capability" to "'UNKNOWN'"),
                mapOf("moderator_id" to "NULL"), mapOf("actor" to "'UNKNOWN'"),
                mapOf("actor" to "'POLICY'"), mapOf("actor" to "'POLICY'", "moderator_id" to "NULL", "action" to "'REJECT'"),
            )) {
                assertSqlState(sql, "23514", insert("moderation_decisions", decision + invalid))
            }
            assertEquals(
                1,
                sql.executeUpdate(
                    insert(
                        "moderation_decisions",
                        decision + mapOf("id" to "'${UUID.randomUUID()}'", "actor" to "'POLICY'", "moderator_id" to "NULL"),
                    ),
                ),
            )
            assertEquals(
                1,
                sql.executeUpdate(
                    insert(
                        "moderation_decisions",
                        decision + mapOf("action" to "'RESTRICT_USER'", "restriction_capability" to "'ALL'"),
                    ),
                ),
            )
            sql.executeQuery("SELECT actor FROM $schema.moderation_decisions WHERE id = '$decisionId'").use {
                assertTrue(it.next())
                assertEquals("MODERATOR", it.getString(1))
            }
            val restriction = mapOf(
                "id" to "'${UUID.randomUUID()}'",
                "user_id" to "'$friend'",
                "capability" to "'ALL'",
                "decision_id" to "'$decisionId'",
                "reason" to "'Правила'",
                "starts_at" to SQL_START,
            )
            assertSqlState(sql, "23514", insert("user_restrictions", restriction + ("capability" to "'UNKNOWN'")))
            assertSqlState(sql, "23514", insert("user_restrictions", restriction + ("expires_at" to SQL_START)))
            assertEquals(1, sql.executeUpdate(insert("user_restrictions", restriction)))
            sql.executeQuery("SELECT count(*) FROM $schema.moderation_settings").use {
                assertTrue(it.next())
                assertEquals(0, it.getInt(1))
            }
            val setting = mapOf("key" to "'foo'", "value" to "'false'", "updated_at" to SQL_START, "updated_by" to "'$owner'")
            assertSqlState(sql, "23514", insert("moderation_settings", setting))
            assertEquals(1, sql.executeUpdate(insert("moderation_settings", setting + ("key" to "'SUBJECT_RESOURCE.premoderation'"))))
            val report = mapOf(
                "id" to "'${UUID.randomUUID()}'",
                "target_type" to "'SUBJECT_RESOURCE'",
                "target_id" to "'$targetId'",
                "reporter_id" to "'$friend'",
                "reason" to "'BROKEN'",
                "created_at" to SQL_START,
            )
            for (column in listOf("target_type", "reason")) {
                assertSqlState(sql, "23514", insert("moderation_reports", report + (column to "'UNKNOWN'")))
            }
            assertEquals(1, sql.executeUpdate(insert("moderation_reports", report)))
            assertSqlState(sql, "23505", insert("moderation_reports", report + ("id" to "'${UUID.randomUUID()}'")))
            assertEquals(
                1,
                sql.executeUpdate(
                    insert(
                        "moderation_reports",
                        report + mapOf("id" to "'${UUID.randomUUID()}'", "target_id" to "'${UUID.randomUUID()}'"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `V4 constrains subject links and revisions and deleting a link cascades its dependents`() {
        withConstraintSchema { schema, sql, owner, friend ->
            fun insert(table: String, values: Map<String, String>) = insertSql(schema, table, values)
            fun count(table: String, where: String): Int = sql.executeQuery("SELECT count(*) FROM $schema.$table WHERE $where").use {
                it.next()
                it.getInt(1)
            }
            val linkId = UUID.randomUUID()
            val link = mapOf(
                "id" to "'$linkId'", "owner_id" to "'$owner'", "subject_id" to "42", "subject_name" to "'Предмет'",
                "period_key" to "'2026-1'", "category" to "'MATERIALS'", "url" to "'https://example.org/a'",
                "normalized_url" to "'https://example.org/a'", "visibility" to "'ALL'",
                "created_at" to SQL_START, "updated_at" to SQL_START,
            )
            for ((column, invalid) in listOf(
                "period_key" to "'2026-3'",
                "category" to "'LINK'",
                "category" to "'chat'",
                "visibility" to "'FRIENDS'",
                "visibility" to "'private'",
                "visibility" to "'GROUP'",
                "flow_id" to "7001",
            )) {
                assertSqlState(sql, "23514", insert("subject_links", link + (column to invalid)))
            }
            // A FLOW link names exactly one flow; other visibilities carry none.
            assertSqlState(sql, "23514", insert("subject_links", link + ("visibility" to "'FLOW'")))
            assertSqlState(sql, "23514", insert("subject_links", link + mapOf("visibility" to "'PRIVATE'", "flow_id" to "7001")))
            for (category in listOf("SCORES", "QUEUE", "MATERIALS", "TASKS", "RECORDINGS", "NOTES", "EXAM", "CHAT", "OTHER")) {
                for (visibility in listOf("PRIVATE", "FLOW", "ALL")) {
                    val flow = if (visibility == "FLOW") mapOf("flow_id" to "7001") else emptyMap()
                    assertEquals(
                        1,
                        sql.executeUpdate(
                            insert(
                                "subject_links",
                                link + mapOf(
                                    "id" to "'${UUID.randomUUID()}'",
                                    "category" to "'$category'",
                                    "visibility" to "'$visibility'",
                                ) + flow,
                            ),
                        ),
                    )
                }
            }
            assertEquals(1, sql.executeUpdate(insert("subject_links", link)))
            assertEquals(1, count("subject_links", "id = '$linkId' AND score = 0 AND title IS NULL AND hidden_at IS NULL"))

            val revision = mapOf(
                "link_id" to "'$linkId'",
                "number" to "1",
                "category" to "'MATERIALS'",
                "url" to "'https://example.org/a'",
                "normalized_url" to "'https://example.org/a'",
                "visibility" to "'ALL'",
                "status" to "'PENDING'",
                "submitted_at" to SQL_START,
            )
            fun revision(number: Int, extra: Map<String, String> = emptyMap()) =
                revision + mapOf("id" to "'${UUID.randomUUID()}'", "number" to "$number") + extra
            for (invalid in listOf(
                mapOf("status" to "'UNKNOWN'"), mapOf("category" to "'LINK'"), mapOf("visibility" to "'FRIENDS'"),
                mapOf("visibility" to "'GROUP'", "flow_id" to "7001"), mapOf("visibility" to "'FLOW'"), mapOf("flow_id" to "7001"),
                mapOf("number" to "0"), mapOf("decided_at" to SQL_END), mapOf("status" to "'APPROVED'"),
            )) {
                assertSqlState(sql, "23514", insert("subject_link_revisions", revision(1, invalid)))
            }
            assertEquals(1, sql.executeUpdate(insert("subject_link_revisions", revision(1))))
            assertSqlState(sql, "23505", insert("subject_link_revisions", revision(2)))
            assertSqlState(
                sql,
                "23505",
                insert(
                    "subject_link_revisions",
                    revision(
                        1,
                        mapOf(
                            "status" to "'APPROVED'",
                            "decided_at" to SQL_END,
                        ),
                    ),
                ),
            )
            sql.executeUpdate(
                "UPDATE $schema.subject_link_revisions SET status = 'APPROVED', decided_at = $SQL_END WHERE link_id = '$linkId'",
            )
            assertEquals(1, sql.executeUpdate(insert("subject_link_revisions", revision(2))))
            for (status in listOf("REJECTED", "WITHDRAWN")) {
                assertEquals(
                    1,
                    sql.executeUpdate(
                        insert(
                            "subject_link_revisions",
                            revision(
                                if (status == "REJECTED") 3 else 4,
                                mapOf("status" to "'$status'", "decided_at" to SQL_END, "visibility" to "'FLOW'", "flow_id" to "7001"),
                            ),
                        ),
                    ),
                )
            }

            val vote = mapOf("link_id" to "'$linkId'", "user_id" to "'$friend'", "value" to "0", "created_at" to SQL_START)
            assertSqlState(sql, "23514", insert("subject_link_votes", vote))
            assertSqlState(sql, "23514", insert("subject_link_votes", vote + ("value" to "2")))
            assertEquals(1, sql.executeUpdate(insert("subject_link_votes", vote + ("value" to "-1"))))
            val pin = mapOf("user_id" to "'$friend'", "subject_id" to "42", "period_key" to "'2026-1'", "link_id" to "'$linkId'")
            assertSqlState(sql, "23514", insert("subject_link_pins", pin + ("period_key" to "'2026-0'")))
            assertEquals(1, sql.executeUpdate(insert("subject_link_pins", pin)))
            assertSqlState(sql, "23505", insert("subject_link_pins", pin))
            val flow = mapOf(
                "user_id" to "'$friend'",
                "subject_id" to "42",
                "period_key" to "'2026-1'",
                "flow_id" to "7001",
                "group_name" to "'P3119'",
                "type_id" to "2",
                "last_seen" to "DATE '2026-09-08'",
            )
            assertSqlState(sql, "23514", insert("user_subject_flows", flow + ("period_key" to "'26-1'")))
            assertEquals(1, sql.executeUpdate(insert("user_subject_flows", flow)))

            assertEquals(1, sql.executeUpdate("DELETE FROM $schema.subject_links WHERE id = '$linkId'"))
            for (table in listOf("subject_link_revisions", "subject_link_votes", "subject_link_pins")) {
                assertEquals(0, count(table, "link_id = '$linkId'"), table)
            }
            assertEquals(1, count("user_subject_flows", "user_id = '$friend'"))
            assertEquals(2, count("users", "id IN ('$owner', '$friend')"))
            // Flows are the user's derived schedule data and leave together with the user.
            assertEquals(1, sql.executeUpdate("DELETE FROM $schema.users WHERE id = '$friend'"))
            assertEquals(0, count("user_subject_flows", "user_id = '$friend'"))
        }
    }
}
