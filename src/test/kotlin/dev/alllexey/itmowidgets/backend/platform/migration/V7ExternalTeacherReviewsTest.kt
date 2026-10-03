package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `V7__external_teacher_reviews.sql`. */
class V7ExternalTeacherReviewsTest : MigrationTestBase() {
    @Test
    fun `V7 constrains external reviews to one row per provider comment`() {
        withConstraintSchema { schema, sql, _, _ ->
            fun insert(table: String, values: Map<String, String>) = insertSql(schema, table, values)
            val review = mapOf(
                "provider" to "'REVIEWS_WORK_GD'", "external_id" to "7001", "teacher_isu" to "100123",
                "teacher_name" to "'Synthetic teacher'", "date_raw" to "'12:18 25.01.2025'", "written_on" to "DATE '2025-01-25'",
                "text" to "'Synthetic review'", "first_seen_at" to SQL_START, "last_seen_at" to SQL_END,
            )
            fun review(extra: Map<String, String> = emptyMap()) = review + ("id" to "'${UUID.randomUUID()}'") + extra
            for (invalid in listOf(
                mapOf("provider" to "'OTHER'"),
                mapOf("teacher_isu" to "99999"),
                mapOf("written_before_year" to "2024"),
                mapOf("last_seen_at" to "TIMESTAMPTZ '2026-09-08T08:59:59Z'"),
            )) {
                assertSqlState(sql, "23514", insert("external_teacher_reviews", review(invalid)))
            }
            assertEquals(1, sql.executeUpdate(insert("external_teacher_reviews", review())))
            assertSqlState(sql, "23505", insert("external_teacher_reviews", review(mapOf("teacher_isu" to "100124"))))
            assertEquals(1, sql.executeUpdate(insert("external_teacher_reviews", review(mapOf("external_id" to "7002")))))

            sql.executeQuery("SELECT provider, running_since, last_outcome, reviews_total FROM $schema.external_review_sync_state").use {
                assertTrue(it.next())
                assertEquals("REVIEWS_WORK_GD", it.getString(1))
                assertEquals(null, it.getObject(2))
                assertEquals(null, it.getObject(3))
                assertEquals(0, it.getInt(4))
                assertFalse(it.next())
            }
            assertSqlState(sql, "23514", "UPDATE $schema.external_review_sync_state SET last_outcome = 'SKIPPED'")
        }
    }
}
