package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V15__flow_link_isu_check.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V15FlowLinkIsuCheckTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("flow_link_isu_check")

    @Test
    fun `only pending flow revisions are queued for the ISU check`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val owner = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu) VALUES ('$owner', 963151)")
        val pendingFlow = revision(schema, owner, "FLOW", "PENDING")
        val approvedFlow = revision(schema, owner, "FLOW", "APPROVED")
        val pendingAll = revision(schema, owner, "ALL", "PENDING")

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        val queued = "isu_check_due_at IS NOT NULL AND isu_check_attempts = 0"
        assertEquals(1, count("SELECT count(*) FROM $schema.subject_link_revisions WHERE id = '$pendingFlow' AND $queued"))
        assertEquals(
            2,
            count(
                "SELECT count(*) FROM $schema.subject_link_revisions WHERE id IN ('$approvedFlow', '$pendingAll') " +
                    "AND isu_check_due_at IS NULL AND isu_check_attempts = 0",
            ),
        )
    }

    @Test
    fun `the check is due only on flow revisions and attempts never go negative`() {
        withConstraintSchema(target = version) { schema, sql, owner, _ ->
            val flow = revision(schema, owner, "FLOW", "PENDING")
            val all = revision(schema, owner, "ALL", "PENDING")

            assertSqlState(sql, "23514", "UPDATE $schema.subject_link_revisions SET isu_check_due_at = $SQL_START WHERE id = '$all'")
            assertSqlState(sql, "23514", "UPDATE $schema.subject_link_revisions SET isu_check_attempts = -1 WHERE id = '$flow'")
            assertEquals(
                1,
                sql.executeUpdate("UPDATE $schema.subject_link_revisions SET isu_check_due_at = $SQL_START WHERE id = '$flow'"),
            )
        }
    }

    /** A link with one revision of [visibility] in [status]; returns the revision id. */
    private fun revision(schema: String, owner: UUID, visibility: String, status: String): UUID {
        val link = UUID.randomUUID()
        val revision = UUID.randomUUID()
        val flow = if (visibility == "FLOW") "7001" else "NULL"
        val decided = if (status == "PENDING") "NULL" else SQL_END
        execute(
            "INSERT INTO $schema.subject_links(id, owner_id, subject_id, subject_name, period_key, category, url, normalized_url, " +
                "visibility, flow_id, created_at, updated_at) VALUES ('$link', '$owner', 42, 'Synthetic subject', '2026-1', " +
                "'MATERIALS', 'https://example.org/$link', 'https://example.org/$link', '$visibility', $flow, $SQL_START, $SQL_START)",
        )
        execute(
            "INSERT INTO $schema.subject_link_revisions(id, link_id, number, category, url, normalized_url, visibility, flow_id, " +
                "status, submitted_at, decided_at) VALUES ('$revision', '$link', 1, 'MATERIALS', 'https://example.org/$link', " +
                "'https://example.org/$link', '$visibility', $flow, '$status', $SQL_START, $decided)",
        )
        return revision
    }
}
