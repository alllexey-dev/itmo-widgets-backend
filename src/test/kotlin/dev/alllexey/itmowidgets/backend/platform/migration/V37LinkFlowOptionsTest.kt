package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V37__link_flow_options.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V37LinkFlowOptionsTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("link_flow_options")

    @Test
    fun `snapshots taken before record the flow of their link revision`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val owner = UUID.randomUUID()
        val link = UUID.randomUUID()
        val flowRevision = UUID.randomUUID()
        val publicRevision = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu) VALUES ('$owner', 963701)")
        execute(
            "INSERT INTO $schema.subject_links (id, owner_id, subject_id, subject_name, period_key, category, url, normalized_url, " +
                "visibility, flow_id, created_at, updated_at) VALUES ('$link', '$owner', 42, 'Synthetic subject', '2026-1', " +
                "'MATERIALS', 'https://example.org', 'https://example.org', 'FLOW', 7002, $SQL_START, $SQL_START)",
        )
        execute(revision(schema, flowRevision, link, 1, "'FLOW'", "7002"))
        execute(revision(schema, publicRevision, link, 2, "'ALL'", "NULL"))
        val snapshot = decisionWithCase(schema, flowRevision, owner, prior = true)
        val plain = decisionWithCase(schema, flowRevision, owner, prior = false)
        val public = decisionWithCase(schema, publicRevision, owner, prior = true)

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        assertEquals(1, count("SELECT count(*) FROM $schema.moderation_decisions WHERE id = '$snapshot' AND prior_flow_id = 7002"))
        assertEquals(1, count("SELECT count(*) FROM $schema.moderation_decisions WHERE id = '$plain' AND prior_flow_id IS NULL"))
        assertEquals(1, count("SELECT count(*) FROM $schema.moderation_decisions WHERE id = '$public' AND prior_flow_id IS NULL"))
        assertEquals(0, count("SELECT count(*) FROM $schema.moderation_decisions WHERE flow_id IS NOT NULL"))
        assertEquals(0, count("SELECT count(*) FROM $schema.subject_link_revisions WHERE flow_options IS NOT NULL"))
    }

    @Test
    fun `flow options are an array of a shared revision and a flow change is a moderator's approval`() {
        withConstraintSchema(target = version) { schema, sql, owner, _ ->
            val link = UUID.randomUUID()
            sql.executeUpdate(
                "INSERT INTO $schema.subject_links (id, owner_id, subject_id, subject_name, period_key, category, url, normalized_url, " +
                    "visibility, created_at, updated_at) VALUES ('$link', '$owner', 42, 'Synthetic subject', '2026-1', " +
                    "'MATERIALS', 'https://example.org', 'https://example.org', 'ALL', $SQL_START, $SQL_START)",
            )
            val options = "'[{\"flowId\": 7002, \"label\": \"P3119\", \"typeId\": 3, \"depth\": 1}]'"
            sql.executeUpdate(revision(schema, UUID.randomUUID(), link, 1, "'ALL'", "NULL", options = options, status = "'WITHDRAWN'"))
            assertSqlState(sql, CHECK_VIOLATION, revision(schema, UUID.randomUUID(), link, 2, "'PRIVATE'", "NULL", options = options))
            assertSqlState(sql, CHECK_VIOLATION, revision(schema, UUID.randomUUID(), link, 3, "'ALL'", "NULL", options = "'{}'"))

            val case = UUID.randomUUID()
            sql.executeUpdate(
                "INSERT INTO $schema.moderation_cases (id, target_type, target_id, status, reason, opened_at, resolved_at) " +
                    "VALUES ('$case', 'SUBJECT_RESOURCE', '${UUID.randomUUID()}', 'RESOLVED', 'SUBMISSION', $SQL_START, $SQL_END)",
            )
            val prior = "'PENDING', NULL, $SQL_START"
            fun decision(action: String, prior: String?, flowId: String, priorFlowId: String = "NULL") =
                "INSERT INTO $schema.moderation_decisions (id, case_id, moderator_id, action, created_at, actor, " +
                    "prior_revision_status, prior_decided_at, prior_edited_at, flow_id, prior_flow_id) VALUES " +
                    "('${UUID.randomUUID()}', '$case', '$owner', $action, $SQL_END, 'MODERATOR', ${prior ?: "NULL, NULL, NULL"}, " +
                    "$flowId, $priorFlowId)"
            sql.executeUpdate(decision("'APPROVE'", prior, flowId = "7001", priorFlowId = "7002"))
            assertSqlState(sql, CHECK_VIOLATION, decision("'REJECT'", prior, flowId = "7001"))
            assertSqlState(sql, CHECK_VIOLATION, decision("'APPROVE'", null, flowId = "7001"))
            assertSqlState(sql, CHECK_VIOLATION, decision("'DISMISS'", null, flowId = "NULL", priorFlowId = "7002"))
        }
    }

    private fun revision(
        schema: String,
        id: UUID,
        link: UUID,
        number: Int,
        visibility: String,
        flowId: String,
        options: String = "NULL",
        status: String = "'APPROVED'",
    ) = "INSERT INTO $schema.subject_link_revisions (id, link_id, number, category, url, normalized_url, visibility, flow_id, status, " +
        "submitted_at, decided_at${if (options == "NULL") "" else ", flow_options"}) VALUES ('$id', '$link', $number, 'MATERIALS', " +
        "'https://example.org', 'https://example.org', $visibility, $flowId, $status, $SQL_START, $SQL_END" +
        "${if (options == "NULL") "" else ", $options::jsonb"})"

    /** A resolved case about [target] with one moderator APPROVE, with or without the V33 snapshot. */
    private fun decisionWithCase(schema: String, target: UUID, moderator: UUID, prior: Boolean): UUID {
        val case = UUID.randomUUID()
        val decision = UUID.randomUUID()
        execute(
            "INSERT INTO $schema.moderation_cases (id, target_type, target_id, status, reason, opened_at, resolved_at) " +
                "VALUES ('$case', 'SUBJECT_RESOURCE', '$target', 'RESOLVED', 'SUBMISSION', $SQL_START, $SQL_END)",
        )
        val snapshot = if (prior) "'PENDING', NULL, $SQL_START" else "NULL, NULL, NULL"
        execute(
            "INSERT INTO $schema.moderation_decisions (id, case_id, moderator_id, action, created_at, actor, prior_revision_status, " +
                "prior_decided_at, prior_edited_at) VALUES " +
                "('$decision', '$case', '$moderator', 'APPROVE', $SQL_END, 'MODERATOR', $snapshot)",
        )
        return decision
    }

    private companion object {
        const val CHECK_VIOLATION = "23514"
    }
}
