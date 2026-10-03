package dev.alllexey.itmowidgets.backend.platform.migration

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `V9__teacher_reviews.sql`. */
class V9TeacherReviewsTest : MigrationTestBase() {
    @Test
    fun `V9 constrains own reviews, votes and the ISU cache`() {
        withConstraintSchema { schema, sql, owner, friend ->
            fun insert(table: String, values: Map<String, String>) = insertSql(schema, table, values)
            fun count(query: String): Int = sql.executeQuery(query).use {
                assertTrue(it.next())
                it.getInt(1)
            }
            val reviewId = UUID.randomUUID()
            val review = mapOf(
                "id" to "'$reviewId'",
                "author_id" to "'$owner'",
                "teacher_isu" to "100123",
                "text" to REVIEW_TEXT,
                "verification_due_at" to SQL_START,
                "created_at" to SQL_START,
                "updated_at" to SQL_START,
            )
            fun review(extra: Map<String, String>) = review + ("id" to "'${UUID.randomUUID()}'") + extra
            for (invalid in listOf(
                mapOf("teacher_isu" to "99999"),
                mapOf("text" to "'${"я".repeat(29)}'"),
                mapOf("verification" to "'VERIFIED'", "verification_due_at" to "NULL"),
                mapOf("verification_due_at" to "NULL"),
                mapOf("verification" to "'UNKNOWN'"),
                mapOf("verification_attempts" to "-1"),
            )) {
                assertSqlState(sql, "23514", insert("teacher_reviews", review(invalid)))
            }
            assertEquals(1, sql.executeUpdate(insert("teacher_reviews", review)))
            assertSqlState(sql, "23505", insert("teacher_reviews", review(emptyMap())))
            sql.executeQuery("SELECT anonymous, score, verification, verification_attempts FROM $schema.teacher_reviews").use {
                assertTrue(it.next())
                assertTrue(it.getBoolean(1))
                assertEquals(0, it.getInt(2))
                assertEquals("PENDING", it.getString(3))
                assertEquals(0, it.getInt(4))
            }

            val revision = mapOf(
                "review_id" to "'$reviewId'",
                "number" to "1",
                "text" to REVIEW_TEXT,
                "status" to "'PENDING'",
                "submitted_at" to SQL_START,
            )
            fun revision(extra: Map<String, String>) = revision + ("id" to "'${UUID.randomUUID()}'") + extra
            assertEquals(1, sql.executeUpdate(insert("teacher_review_revisions", revision(emptyMap()))))
            assertSqlState(sql, "23505", insert("teacher_review_revisions", revision(mapOf("number" to "2"))))
            assertSqlState(sql, "23514", insert("teacher_review_revisions", revision(mapOf("number" to "3", "decided_at" to SQL_END))))
            assertSqlState(sql, "23514", insert("teacher_review_revisions", revision(mapOf("number" to "4", "status" to "'APPROVED'"))))

            val friendReview = UUID.randomUUID()
            assertEquals(
                1,
                sql.executeUpdate(
                    insert(
                        "teacher_reviews",
                        review + mapOf("id" to "'$friendReview'", "author_id" to "'$friend'"),
                    ),
                ),
            )
            val externalReview = UUID.randomUUID()
            assertEquals(
                1,
                sql.executeUpdate(
                    insert(
                        "external_teacher_reviews",
                        mapOf(
                            "id" to "'$externalReview'",
                            "provider" to "'REVIEWS_WORK_GD'", "external_id" to "9001", "teacher_isu" to "100123",
                            "teacher_name" to "'Synthetic teacher'",
                            "date_raw" to "''", "text" to "'Synthetic review'", "first_seen_at" to SQL_START, "last_seen_at" to SQL_START,
                        ),
                    ),
                ),
            )
            assertEquals(0, count("SELECT score FROM $schema.external_teacher_reviews WHERE id = '$externalReview'"))
            val vote = mapOf("review_id" to "'$reviewId'", "user_id" to "'$friend'", "value" to "1", "created_at" to SQL_START)
            assertSqlState(sql, "23514", insert("teacher_review_votes", vote + ("value" to "2")))
            assertSqlState(
                sql,
                "23514",
                insert(
                    "external_teacher_review_votes",
                    vote + mapOf("review_id" to "'$externalReview'", "value" to "0"),
                ),
            )
            assertEquals(1, sql.executeUpdate(insert("teacher_review_votes", vote)))
            assertEquals(
                1,
                sql.executeUpdate(
                    insert(
                        "teacher_review_votes",
                        vote + mapOf("review_id" to "'$friendReview'", "user_id" to "'$owner'"),
                    ),
                ),
            )
            assertEquals(
                1,
                sql.executeUpdate(
                    insert(
                        "external_teacher_review_votes",
                        vote + mapOf(
                            "review_id" to "'$externalReview'",
                            "user_id" to "'$owner'",
                            "value" to "-1",
                        ),
                    ),
                ),
            )
            assertSqlState(sql, "23514", insert("teacher_review_flows", mapOf("review_id" to "'$reviewId'", "flow_id" to "0")))
            assertEquals(1, sql.executeUpdate(insert("teacher_review_flows", mapOf("review_id" to "'$reviewId'", "flow_id" to "93724"))))

            val case = mapOf(
                "id" to "'${UUID.randomUUID()}'",
                "target_type" to "'TEACHER_REVIEW'",
                "target_id" to "'$reviewId'",
                "status" to "'OPEN'",
                "reason" to "'SUBMISSION'",
                "opened_at" to SQL_START,
            )
            assertSqlState(sql, "23514", insert("moderation_cases", case + ("target_type" to "'OTHER_TYPE'")))
            assertEquals(1, sql.executeUpdate(insert("moderation_cases", case)))
            val report = mapOf(
                "id" to "'${UUID.randomUUID()}'",
                "target_type" to "'TEACHER_REVIEW'",
                "target_id" to "'$reviewId'",
                "reporter_id" to "'$friend'",
                "reason" to "'WRONG_TEACHER'",
                "created_at" to SQL_START,
            )
            assertSqlState(sql, "23514", insert("moderation_reports", report + ("target_type" to "'OTHER_TYPE'")))
            assertSqlState(sql, "23514", insert("moderation_reports", report + ("reason" to "'UNKNOWN'")))
            assertEquals(1, sql.executeUpdate(insert("moderation_reports", report)))
            assertEquals(
                1,
                sql.executeUpdate(
                    insert(
                        "moderation_reports",
                        report + mapOf(
                            "id" to "'${UUID.randomUUID()}'",
                            "target_id" to "'$friendReview'",
                            "reason" to "'OFFENSIVE'",
                        ),
                    ),
                ),
            )

            assertSqlState(sql, "23514", insert("isu_potoks", mapOf("potok_id" to "0")))
            assertEquals(1, sql.executeUpdate(insert("isu_potoks", mapOf("potok_id" to "93724", "teachers_checked_at" to SQL_START))))
            assertEquals(1, sql.executeUpdate(insert("isu_potok_teachers", mapOf("potok_id" to "93724", "teacher_isu" to "100123"))))
            assertEquals(1, sql.executeUpdate(insert("isu_potok_members", mapOf("potok_id" to "93724", "isu" to "920001"))))
            assertSqlState(sql, "23503", insert("isu_potok_members", mapOf("potok_id" to "93725", "isu" to "920001")))
            assertEquals(1, sql.executeUpdate("DELETE FROM $schema.isu_potoks WHERE potok_id = 93724"))
            assertEquals(
                0,
                count("SELECT count(*) FROM $schema.isu_potok_teachers") + count("SELECT count(*) FROM $schema.isu_potok_members"),
            )

            assertEquals(1, sql.executeUpdate("DELETE FROM $schema.users WHERE id = '$owner'"))
            assertEquals(0, count("SELECT count(*) FROM $schema.teacher_reviews WHERE author_id = '$owner'"))
            assertEquals(0, count("SELECT count(*) FROM $schema.teacher_review_revisions"))
            assertEquals(0, count("SELECT count(*) FROM $schema.teacher_review_flows"))
            assertEquals(0, count("SELECT count(*) FROM $schema.teacher_review_votes"))
            assertEquals(0, count("SELECT count(*) FROM $schema.external_teacher_review_votes"))
            assertEquals(1, count("SELECT count(*) FROM $schema.teacher_reviews WHERE id = '$friendReview'"))
        }
    }

    companion object {
        private const val REVIEW_TEXT = "'Синтетический отзыв о преподавателе для теста'"
    }
}
