package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V33__moderation_decision_reverts.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V33ModerationDecisionRevertsTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("moderation_decision_reverts")

    @Test
    fun `decisions, reports and cases written before keep their rows without a snapshot`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val moderator = UUID.randomUUID()
        val case = UUID.randomUUID()
        val decision = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu) VALUES ('$moderator', 963401)")
        execute(insertSql(schema, "moderation_cases", caseFields(case, "WITHDRAWN")))
        execute(insertSql(schema, "moderation_decisions", decisionFields(decision, case, moderator, "'DISMISS'")))
        execute(insertSql(schema, "moderation_reports", reportFields(case, moderator, dismissedAt = SQL_START)))

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        assertEquals(
            1,
            count(
                "SELECT count(*) FROM $schema.moderation_decisions WHERE id = '$decision' AND prior_revision_status IS NULL " +
                    "AND prior_edited_at IS NULL AND prior_case_resolved_at IS NULL",
            ),
        )
        assertEquals(1, count("SELECT count(*) FROM $schema.moderation_reports WHERE dismissed_decision_id IS NULL"))
        assertEquals(1, count("SELECT count(*) FROM $schema.moderation_cases WHERE id = '$case' AND withdrawn_by_decision IS NULL"))
        assertEquals(0, count("SELECT count(*) FROM $schema.moderation_decision_reverts"))
    }

    @Test
    fun `only a revertible decision of a moderator records a whole snapshot`() {
        withConstraintSchema(target = version) { schema, sql, owner, _ ->
            val case = UUID.randomUUID()
            sql.executeUpdate(insertSql(schema, "moderation_cases", caseFields(case, "RESOLVED")))
            fun decision(action: String, prior: Map<String, String>, actor: String = "'MODERATOR'", moderator: UUID? = owner) = insertSql(
                schema,
                "moderation_decisions",
                decisionFields(UUID.randomUUID(), case, moderator, action) + ("actor" to actor) + prior,
            )
            val approved = mapOf(
                "prior_revision_status" to "'PENDING'",
                "prior_decided_at" to "NULL",
                "prior_note" to "NULL",
                "prior_hidden_at" to "NULL",
                "prior_edited_at" to SQL_START,
                "prior_case_resolved_at" to "NULL",
            )
            for (invalid in listOf(
                decision("'RESTRICT_USER'", approved + ("restriction_capability" to "'VOTE'")),
                decision("'HIDE_ALL_BY_USER'", approved),
                decision("'APPROVE'", approved, actor = "'POLICY'", moderator = null),
                decision("'APPROVE'", approved + ("prior_revision_status" to "'DRAFT'")),
                decision("'APPROVE'", approved + ("prior_decided_at" to SQL_START)),
                decision("'APPROVE'", approved + ("prior_revision_status" to "'APPROVED'")),
                decision("'APPROVE'", approved + ("prior_edited_at" to "NULL")),
                decision("'APPROVE'", approved + ("prior_revision_status" to "NULL")),
            )) {
                assertSqlState(sql, "23514", invalid)
            }
            for (action in listOf("'APPROVE'", "'REJECT'", "'HIDE'", "'RESTORE'", "'DISMISS'")) {
                assertEquals(1, sql.executeUpdate(decision(action, approved)))
            }
            val resolved = approved + mapOf(
                "prior_revision_status" to "'APPROVED'",
                "prior_decided_at" to SQL_START,
                "prior_note" to "'Synthetic note'",
                "prior_hidden_at" to SQL_START,
                "prior_case_resolved_at" to SQL_END,
            )
            assertEquals(1, sql.executeUpdate(decision("'RESTORE'", resolved)))
            assertEquals(1, sql.executeUpdate(decision("'APPROVE'", emptyMap())), "Written by an image before V33")
        }
    }

    @Test
    fun `a decision is reverted at most once, by a user, and the dismissal and the withdrawal name a decision`() {
        withConstraintSchema(target = version) { schema, sql, owner, friend ->
            val case = UUID.randomUUID()
            val decision = UUID.randomUUID()
            sql.executeUpdate(insertSql(schema, "moderation_cases", caseFields(case, "RESOLVED")))
            sql.executeUpdate(insertSql(schema, "moderation_decisions", decisionFields(decision, case, owner, "'DISMISS'")))
            fun revert(decisionId: UUID, by: UUID) = insertSql(
                schema,
                "moderation_decision_reverts",
                mapOf("decision_id" to "'$decisionId'", "reverted_by" to "'$by'", "reverted_at" to SQL_END),
            )

            assertSqlState(sql, "23503", revert(UUID.randomUUID(), owner))
            assertSqlState(sql, "23503", revert(decision, UUID.randomUUID()))
            assertEquals(1, sql.executeUpdate(revert(decision, friend)))
            assertSqlState(sql, "23505", revert(decision, owner))

            val dismissed = reportFields(case, friend, dismissedAt = SQL_START) + ("dismissed_decision_id" to "'$decision'")
            assertSqlState(sql, "23514", insertSql(schema, "moderation_reports", dismissed + ("dismissed_at" to "NULL")))
            assertSqlState(
                sql,
                "23503",
                insertSql(schema, "moderation_reports", dismissed + ("dismissed_decision_id" to "'${UUID.randomUUID()}'")),
            )
            assertEquals(1, sql.executeUpdate(insertSql(schema, "moderation_reports", dismissed)))

            val withdrawn = caseFields(UUID.randomUUID(), "WITHDRAWN") + ("withdrawn_by_decision" to "'$decision'")
            assertSqlState(
                sql,
                "23514",
                insertSql(schema, "moderation_cases", withdrawn + mapOf("status" to "'OPEN'", "resolved_at" to "NULL")),
            )
            assertEquals(1, sql.executeUpdate(insertSql(schema, "moderation_cases", withdrawn)))
        }
    }

    private fun caseFields(id: UUID, status: String) = mapOf(
        "id" to "'$id'",
        "target_type" to "'SUBJECT_RESOURCE'",
        "target_id" to "'${UUID.randomUUID()}'",
        "status" to "'$status'",
        "reason" to "'REPORTS'",
        "opened_at" to SQL_START,
        "resolved_at" to if (status == "OPEN") "NULL" else SQL_END,
    )

    private fun decisionFields(id: UUID, case: UUID, moderator: UUID?, action: String) = mapOf(
        "id" to "'$id'",
        "case_id" to "'$case'",
        "moderator_id" to (moderator?.let { "'$it'" } ?: "NULL"),
        "action" to action,
        "created_at" to SQL_END,
    )

    private fun reportFields(case: UUID, reporter: UUID, dismissedAt: String) = mapOf(
        "id" to "'${UUID.randomUUID()}'",
        "target_type" to "'SUBJECT_RESOURCE'",
        "target_id" to "'$case'",
        "reporter_id" to "'$reporter'",
        "reason" to "'SPAM'",
        "created_at" to SQL_START,
        "dismissed_at" to dismissedAt,
    )
}
