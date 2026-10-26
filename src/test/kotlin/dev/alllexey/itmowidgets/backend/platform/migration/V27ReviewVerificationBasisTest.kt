package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** `V27__review_verification_basis.sql`; the version is looked up by name, so a renumbering at merge needs no edit here. */
class V27ReviewVerificationBasisTest : MigrationTestBase() {
    private val version = MigrationScripts.versionOf("review_verification_basis")

    @Test
    fun `verified reviews get the ISU basis and reviews of coaches are verified by the sport catalog`() {
        val schema = newSchemaName()
        isolatedFlyway(schema, target = MigrationScripts.before(version)).migrate()
        val author = UUID.randomUUID()
        execute("INSERT INTO $schema.users(id, isu, name) VALUES ('$author', 966401, 'Synthetic user')")
        execute("INSERT INTO $schema.sport_sections(id, name) VALUES (1, 'Synthetic section')")
        execute("INSERT INTO $schema.sport_teachers(isu, name) VALUES (471031, 'Synthetic coach')")
        execute("INSERT INTO $schema.sport_time_slots(id, time_start, time_end) VALUES (1, '12:00', '13:00')")
        execute(insertSql(schema, "sport_lessons", lessonFields(id = 1) + ("teacher_isu" to "471031")))
        val flow = review(schema, author, 100123, "'VERIFIED'", flowId = "93724", dueAt = "NULL")
        val pending = review(schema, author, 100124, "'PENDING'")
        val coachPending = review(schema, author, 471031, "'PENDING'")

        assertEquals(1, isolatedFlyway(schema, target = version).migrate().migrationsExecuted)

        assertEquals(1, count("SELECT count(*) FROM $schema.teacher_reviews WHERE id = '$flow' AND verified_basis = 'ISU_FLOW'"))
        assertEquals(1, count("SELECT count(*) FROM $schema.teacher_reviews WHERE id = '$pending' AND verified_basis IS NULL"))
        assertEquals(
            1,
            count(
                "SELECT count(*) FROM $schema.teacher_reviews WHERE id = '$coachPending' AND verification = 'VERIFIED' " +
                    "AND verified_basis = 'SPORT_TEACHER' AND verified_flow_id IS NULL AND verification_due_at IS NULL",
            ),
        )
    }

    @Test
    fun `the basis matches the verification and the flow, and a row written by an older image still fits`() {
        withConstraintSchema { schema, sql, owner, _ ->
            var teacher = 100200
            fun row(verification: String, basis: String, flowId: String, dueAt: String = "NULL") = insertSql(
                schema,
                "teacher_reviews",
                mapOf(
                    "id" to "'${UUID.randomUUID()}'", "author_id" to "'$owner'", "teacher_isu" to "${teacher++}", "text" to REVIEW_TEXT,
                    "verification" to verification, "verified_basis" to basis, "verified_flow_id" to flowId,
                    "verification_due_at" to dueAt, "created_at" to SQL_START, "updated_at" to SQL_START,
                ),
            )
            for (invalid in listOf(
                row("'VERIFIED'", "'ISU_FLOW'", "NULL"),
                row("'VERIFIED'", "'SPORT_TEACHER'", "93724"),
                row("'PENDING'", "'SPORT_TEACHER'", "NULL", SQL_START),
                row("'VERIFIED'", "'OTHER'", "93724"),
                row("'VERIFIED'", "NULL", "NULL"),
            )) {
                assertSqlState(sql, "23514", invalid)
            }
            assertEquals(1, sql.executeUpdate(row("'VERIFIED'", "'ISU_FLOW'", "93724")))
            assertEquals(1, sql.executeUpdate(row("'VERIFIED'", "'SPORT_TEACHER'", "NULL")))
            assertEquals(1, sql.executeUpdate(row("'VERIFIED'", "NULL", "93724")), "Written by an image before V27")
            assertEquals(1, sql.executeUpdate(row("'UNVERIFIED'", "NULL", "NULL")))
        }
    }

    private fun review(
        schema: String,
        author: UUID,
        teacher: Int,
        verification: String,
        flowId: String = "NULL",
        dueAt: String = SQL_START,
    ): UUID {
        val id = UUID.randomUUID()
        execute(
            insertSql(
                schema,
                "teacher_reviews",
                mapOf(
                    "id" to "'$id'", "author_id" to "'$author'", "teacher_isu" to "$teacher", "text" to REVIEW_TEXT,
                    "verification" to verification, "verified_flow_id" to flowId, "verification_due_at" to dueAt,
                    "created_at" to SQL_START, "updated_at" to SQL_START,
                ),
            ),
        )
        return id
    }

    private companion object {
        const val REVIEW_TEXT = "'Синтетический отзыв о преподавателе для теста'"
    }
}
