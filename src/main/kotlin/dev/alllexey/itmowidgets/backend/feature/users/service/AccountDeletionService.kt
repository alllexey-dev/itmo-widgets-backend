package dev.alllexey.itmowidgets.backend.feature.users.service

import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Deletes one account the way `docs/ops/account-deletion.sql` does: the same statements in the same order, with the
 * same locks and `lock_timeout`, in one transaction (AccountDeletionServiceTest compares the two). Published links
 * and reviews move to the per-deletion placeholder (`isu = -<ISU>`), everything else of the account goes, and one
 * `admin_audit` row records the deletion against the placeholder. A call for an ISU without an account changes
 * nothing. Native SQL bypasses Hibernate: the caller must not hold entities of the account in its persistence context.
 */
@Service
class AccountDeletionService(private val jdbc: JdbcTemplate, private val clock: Clock) {
    @Transactional
    fun delete(isu: Int) {
        require(isu > 0) { "isu must be a positive ISU number" }
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

        jdbc.execute("SET LOCAL lock_timeout = '5s'")
        // The services take the moderation lock of each target type before the user row (ModerationService.lock).
        jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('moderation:SUBJECT_RESOURCE', 0))")
        jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('moderation:TEACHER_REVIEW', 0))")
        val account = jdbc.query(
            "SELECT id, created_at FROM users WHERE isu = ? FOR UPDATE",
            { rs, _ -> Account(rs.getObject("id", UUID::class.java), rs.getObject("created_at", OffsetDateTime::class.java)) },
            isu,
        ).singleOrNull() ?: return

        val placeholder = placeholderFor(isu, account.createdAt)
        deleteVotes(account.id)
        deleteOrMoveLinks(account.id, placeholder, now)
        deleteOrMoveReviews(account.id, placeholder, now)
        moveHistory(account.id, placeholder, isu)
        deleteRest(account.id, isu)
        jdbc.update(
            "INSERT INTO admin_audit (id, actor_id, action, target, details, created_at) VALUES (?, ?, ?, ?, NULL, ?)",
            UUID.randomUUID(),
            placeholder,
            AUDIT_ACTION,
            "user:${-isu}",
            now,
        )
    }

    /** The placeholder keeps the account's registration time, so registration statistics stay as they were. */
    private fun placeholderFor(isu: Int, createdAt: OffsetDateTime): UUID {
        jdbc.update(
            """
            INSERT INTO users (id, isu, name, picture_url, created_at)
            VALUES (gen_random_uuid(), ?, ?, NULL, ?)
            ON CONFLICT (isu) DO NOTHING
            """,
            -isu,
            PLACEHOLDER_NAME,
            createdAt,
        )
        val placeholder = jdbc.queryForObject("SELECT id FROM users WHERE isu = ?", UUID::class.java, -isu)!!
        // Hibernate needs the settings row of every user; the placeholder shares nothing.
        jdbc.update(
            """
            INSERT INTO user_settings (user_id, auto_sign_limit, sport_visibility, schedule_visibility, friends_visibility)
            VALUES (?, 0, 'NOBODY', 'NOBODY', 'NOBODY')
            ON CONFLICT (user_id) DO NOTHING
            """,
            placeholder,
        )
        return placeholder
    }

    /** Votes of the account, with the scores of the voted records recalculated (as the vote services do). */
    private fun deleteVotes(user: UUID) {
        var voted = ids("SELECT link_id FROM subject_link_votes WHERE user_id = ?", user)
        jdbc.update("DELETE FROM subject_link_votes WHERE user_id = ?", user)
        jdbc.update(
            """
            UPDATE subject_links l SET score = coalesce((SELECT sum(v.value) FROM subject_link_votes v WHERE v.link_id = l.id), 0)
            WHERE l.id = ANY (?)
            """,
            array(voted),
        )

        voted = ids("SELECT review_id FROM teacher_review_votes WHERE user_id = ?", user)
        jdbc.update("DELETE FROM teacher_review_votes WHERE user_id = ?", user)
        jdbc.update(
            """
            UPDATE teacher_reviews t SET score = coalesce((SELECT sum(v.value) FROM teacher_review_votes v WHERE v.review_id = t.id), 0)
            WHERE t.id = ANY (?)
            """,
            array(voted),
        )

        voted = ids("SELECT review_id FROM external_teacher_review_votes WHERE user_id = ?", user)
        jdbc.update("DELETE FROM external_teacher_review_votes WHERE user_id = ?", user)
        jdbc.update(
            """
            UPDATE external_teacher_reviews e
            SET score = coalesce((SELECT sum(v.value) FROM external_teacher_review_votes v WHERE v.review_id = e.id), 0)
            WHERE e.id = ANY (?)
            """,
            array(voted),
        )
    }

    /** Subject links: what others see now (SubjectLinkViews.shown) moves to the placeholder, the rest goes. */
    private fun deleteOrMoveLinks(user: UUID, placeholder: UUID, now: OffsetDateTime) {
        val keep = array(
            ids(
                """
                SELECT l.id FROM subject_links l
                WHERE l.owner_id = ? AND l.visibility <> 'PRIVATE' AND l.hidden_at IS NULL
                  AND EXISTS (SELECT 1 FROM subject_link_revisions r WHERE r.link_id = l.id AND r.status = 'APPROVED')
                """,
                user,
            ),
        )
        val drop = array(ids("SELECT l.id FROM subject_links l WHERE l.owner_id = ? AND NOT (l.id = ANY (?))", user, keep))

        // As SubjectLinkService.delete: open cases withdrawn, reports removed; revisions, votes and pins cascade.
        jdbc.update(
            """
            UPDATE moderation_cases SET status = 'WITHDRAWN', resolved_at = ?
            WHERE target_type = 'SUBJECT_RESOURCE' AND status = 'OPEN'
              AND target_id IN (SELECT r.id FROM subject_link_revisions r WHERE r.link_id = ANY (?))
            """,
            now,
            drop,
        )
        jdbc.update(
            """
            DELETE FROM moderation_reports
            WHERE target_type = 'SUBJECT_RESOURCE'
              AND target_id IN (SELECT r.id FROM subject_link_revisions r WHERE r.link_id = ANY (?))
            """,
            drop,
        )
        jdbc.update("DELETE FROM subject_links WHERE id = ANY (?)", drop)

        // Kept links: a pending revision is withdrawn (as ModerationService.withdraw), the row takes the shown
        // content instead of an unpublished draft, and the owner becomes the placeholder.
        val pending = array(
            ids("SELECT r.id FROM subject_link_revisions r WHERE r.link_id = ANY (?) AND r.status = 'PENDING'", keep),
        )
        jdbc.update("UPDATE subject_link_revisions SET status = 'WITHDRAWN', decided_at = ? WHERE id = ANY (?)", now, pending)
        jdbc.update(
            """
            UPDATE moderation_cases SET status = 'WITHDRAWN', resolved_at = ?
            WHERE target_type = 'SUBJECT_RESOURCE' AND status = 'OPEN' AND target_id = ANY (?)
            """,
            now,
            pending,
        )
        jdbc.update(
            """
            UPDATE subject_links l
            SET owner_id = ?, category = a.category, url = a.url, normalized_url = a.normalized_url,
                title = a.title, visibility = a.visibility, flow_id = a.flow_id
            FROM (
                SELECT DISTINCT ON (r.link_id) r.link_id, r.category, r.url, r.normalized_url, r.title, r.visibility, r.flow_id
                FROM subject_link_revisions r
                WHERE r.link_id = ANY (?) AND r.status = 'APPROVED'
                ORDER BY r.link_id, r.number DESC
            ) a
            WHERE l.id = a.link_id
            """,
            placeholder,
            keep,
        )
        // A FLOW link is labelled with its flow name from the owner's schedule (SubjectLinkViews.Labels):
        // the placeholder keeps only those flows.
        jdbc.update(
            """
            INSERT INTO user_subject_flows (user_id, subject_id, period_key, flow_id, group_name, type_id, last_seen)
            SELECT ?, f.subject_id, f.period_key, f.flow_id, f.group_name, f.type_id, f.last_seen
            FROM user_subject_flows f
            JOIN subject_links l ON l.subject_id = f.subject_id AND l.period_key = f.period_key AND l.flow_id = f.flow_id
            WHERE f.user_id = ? AND l.id = ANY (?)
            ON CONFLICT DO NOTHING
            """,
            placeholder,
            user,
            keep,
        )
    }

    /** Teacher reviews: what others see now (TeacherReviewViews.shown) moves to the placeholder, the rest goes. */
    private fun deleteOrMoveReviews(user: UUID, placeholder: UUID, now: OffsetDateTime) {
        val keep = array(
            ids(
                """
                SELECT t.id FROM teacher_reviews t
                WHERE t.author_id = ? AND t.hidden_at IS NULL
                  AND EXISTS (SELECT 1 FROM teacher_review_revisions r WHERE r.review_id = t.id AND r.status = 'APPROVED')
                """,
                user,
            ),
        )
        val drop = array(ids("SELECT t.id FROM teacher_reviews t WHERE t.author_id = ? AND NOT (t.id = ANY (?))", user, keep))

        // As TeacherReviewService.delete: open cases withdrawn, reports removed; revisions, votes and flows cascade.
        jdbc.update(
            """
            UPDATE moderation_cases SET status = 'WITHDRAWN', resolved_at = ?
            WHERE target_type = 'TEACHER_REVIEW' AND status = 'OPEN'
              AND target_id IN (SELECT r.id FROM teacher_review_revisions r WHERE r.review_id = ANY (?))
            """,
            now,
            drop,
        )
        jdbc.update(
            """
            DELETE FROM moderation_reports
            WHERE target_type = 'TEACHER_REVIEW'
              AND target_id IN (SELECT r.id FROM teacher_review_revisions r WHERE r.review_id = ANY (?))
            """,
            drop,
        )
        jdbc.update("DELETE FROM teacher_reviews WHERE id = ANY (?)", drop)

        // Kept reviews: as kept links, plus anonymous. The ISU check needs the author's ISU, so a pending check
        // ends as unverified and its candidate flows go.
        val pending = array(
            ids("SELECT r.id FROM teacher_review_revisions r WHERE r.review_id = ANY (?) AND r.status = 'PENDING'", keep),
        )
        jdbc.update("UPDATE teacher_review_revisions SET status = 'WITHDRAWN', decided_at = ? WHERE id = ANY (?)", now, pending)
        jdbc.update(
            """
            UPDATE moderation_cases SET status = 'WITHDRAWN', resolved_at = ?
            WHERE target_type = 'TEACHER_REVIEW' AND status = 'OPEN' AND target_id = ANY (?)
            """,
            now,
            pending,
        )
        jdbc.update(
            """
            UPDATE teacher_reviews t
            SET author_id = ?, anonymous = true, subject_title = a.subject_title, text = a.text,
                verification = CASE WHEN t.verification = 'PENDING' THEN 'UNVERIFIED' ELSE t.verification END,
                verification_due_at = NULL
            FROM (
                SELECT DISTINCT ON (r.review_id) r.review_id, r.subject_title, r.text
                FROM teacher_review_revisions r
                WHERE r.review_id = ANY (?) AND r.status = 'APPROVED'
                ORDER BY r.review_id, r.number DESC
            ) a
            WHERE t.id = a.review_id
            """,
            placeholder,
            keep,
        )
        jdbc.update("DELETE FROM teacher_review_flows WHERE review_id = ANY (?)", keep)
    }

    /** Moderation history and the admin audit stay whole and name the placeholder. */
    private fun moveHistory(user: UUID, placeholder: UUID, isu: Int) {
        jdbc.update("UPDATE moderation_reports SET reporter_id = ? WHERE reporter_id = ?", placeholder, user)
        jdbc.update("UPDATE moderation_decisions SET moderator_id = ? WHERE moderator_id = ?", placeholder, user)
        jdbc.update("UPDATE user_restrictions SET revoked_by = ? WHERE revoked_by = ?", placeholder, user)
        jdbc.update("UPDATE moderation_settings SET updated_by = ? WHERE updated_by = ?", placeholder, user)
        jdbc.update("UPDATE admin_audit SET actor_id = ? WHERE actor_id = ?", placeholder, user)
        // Role changes name their target as user:<ISU> (AdminUsersService).
        jdbc.update("UPDATE admin_audit SET target = ? WHERE target = ?", "user:${-isu}", "user:$isu")
    }

    private fun deleteRest(user: UUID, isu: Int) {
        jdbc.update("DELETE FROM user_restrictions WHERE user_id = ?", user)
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", user)
        jdbc.update("DELETE FROM subject_link_pins WHERE user_id = ?", user)
        jdbc.update("DELETE FROM user_subject_flows WHERE user_id = ?", user)
        jdbc.update("DELETE FROM friendships WHERE requester_id = ? OR addressee_id = ?", user, user)
        jdbc.update("DELETE FROM devices WHERE user_id = ?", user)
        jdbc.update("DELETE FROM sport_auto_sign_entries WHERE user_id = ?", user)
        jdbc.update("DELETE FROM sport_free_sign_entries WHERE user_id = ?", user)
        jdbc.update("DELETE FROM user_sport_lessons WHERE user_id = ?", user)
        jdbc.update("DELETE FROM web_sessions WHERE user_id = ?", user)
        jdbc.update("DELETE FROM web_login_challenges WHERE approved_by = ?", user)
        // Lessons are keyed by ISU without a foreign key.
        jdbc.update("DELETE FROM lessons WHERE user_isu = ?", isu)
        jdbc.update("DELETE FROM user_groups WHERE user_id = ?", user)
        jdbc.update("DELETE FROM user_settings WHERE user_id = ?", user)
        // app_settings, service_credentials and teacher_summaries set their editor to NULL themselves.
        // Any reference this service does not know about fails here and rolls everything back.
        jdbc.update("DELETE FROM users WHERE id = ?", user)
    }

    private fun ids(sql: String, vararg args: Any): List<UUID> = jdbc.queryForList(sql, UUID::class.java, *args)

    private fun array(ids: List<UUID>): java.sql.Array = jdbc.execute(ConnectionCallback { it.createArrayOf("uuid", ids.toTypedArray()) })!!

    private class Account(val id: UUID, val createdAt: OffsetDateTime)

    private companion object {
        const val PLACEHOLDER_NAME = "Удалённый пользователь"
        const val AUDIT_ACTION = "ACCOUNT_DELETED"
    }
}
