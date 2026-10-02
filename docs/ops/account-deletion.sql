-- Deletes one ITMO.Widgets account by ISU in a single transaction (docs/ops/account-deletion.md).
--
--   psql -X -v ON_ERROR_STOP=1 -v isu=<ISU> -f docs/ops/account-deletion.sql
--
-- Published content stays: subject links that others currently see (FLOW or ALL, not hidden, with an
-- approved revision) and teacher reviews that others currently see (not hidden, with an approved revision)
-- move to a per-deletion placeholder user with isu = -<ISU> and the name «Удалённый пользователь»; the
-- reviews become anonymous. Everything else of the account is deleted the way the services delete it:
-- open moderation cases of removed revisions are withdrawn, reports on them are removed. Moderation history
-- and the admin audit point at the placeholder instead of the account.
--
-- Covers the schema of V1–V10. A later migration that references users needs a matching change here and in
-- AccountDeletionRunbookTest. Running it again for the same ISU changes nothing; an account recreated after
-- the deletion is deleted into the same placeholder.

\set ON_ERROR_STOP on

BEGIN;

SET LOCAL lock_timeout = '5s';
-- Carried into the DO block below; the cast there rejects anything but an integer.
SET LOCAL account_deletion.isu = :'isu';

CREATE TEMP TABLE account_deletion_report (
    step serial PRIMARY KEY,
    table_name text NOT NULL,
    action text NOT NULL,
    affected bigint NOT NULL
);

DO $deletion$
DECLARE
    v_isu integer := current_setting('account_deletion.isu')::integer;
    v_now timestamptz := now();
    v_user uuid;
    v_created timestamptz;
    v_placeholder uuid;
    v_rows bigint;
    v_ids uuid[];
    v_keep_links uuid[];
    v_drop_links uuid[];
    v_keep_reviews uuid[];
    v_drop_reviews uuid[];
BEGIN
    IF v_isu IS NULL OR v_isu <= 0 THEN
        RAISE EXCEPTION 'isu must be a positive ISU number';
    END IF;

    -- The services take the moderation lock of each target type before the user row (ModerationService.lock).
    PERFORM pg_advisory_xact_lock(hashtextextended('moderation:SUBJECT_RESOURCE', 0));
    PERFORM pg_advisory_xact_lock(hashtextextended('moderation:TEACHER_REVIEW', 0));

    SELECT id, created_at INTO v_user, v_created FROM users WHERE isu = v_isu FOR UPDATE;
    IF v_user IS NULL THEN
        INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('users', 'not found, nothing changed', 0);
        RETURN;
    END IF;

    -- The placeholder keeps the account's registration time, so registration statistics stay as they were.
    INSERT INTO users (id, isu, name, picture_url, created_at)
    VALUES (gen_random_uuid(), -v_isu, 'Удалённый пользователь', NULL, v_created)
    ON CONFLICT (isu) DO NOTHING;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('users', 'placeholder created', v_rows);
    SELECT id INTO STRICT v_placeholder FROM users WHERE isu = -v_isu;
    -- Hibernate needs the settings row of every user; the placeholder shares nothing.
    INSERT INTO user_settings (user_id, auto_sign_limit, sport_visibility, schedule_visibility, friends_visibility)
    VALUES (v_placeholder, 0, 'NOBODY', 'NOBODY', 'NOBODY')
    ON CONFLICT (user_id) DO NOTHING;

    -- Votes of the account, with the scores of the voted records recalculated (as the vote services do).
    SELECT coalesce(array_agg(link_id), '{}') INTO v_ids FROM subject_link_votes WHERE user_id = v_user;
    DELETE FROM subject_link_votes WHERE user_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('subject_link_votes', 'deleted', v_rows);
    UPDATE subject_links l SET score = coalesce((SELECT sum(v.value) FROM subject_link_votes v WHERE v.link_id = l.id), 0)
    WHERE l.id = ANY (v_ids);
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('subject_links', 'score recalculated', v_rows);

    SELECT coalesce(array_agg(review_id), '{}') INTO v_ids FROM teacher_review_votes WHERE user_id = v_user;
    DELETE FROM teacher_review_votes WHERE user_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('teacher_review_votes', 'deleted', v_rows);
    UPDATE teacher_reviews t SET score = coalesce((SELECT sum(v.value) FROM teacher_review_votes v WHERE v.review_id = t.id), 0)
    WHERE t.id = ANY (v_ids);
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('teacher_reviews', 'score recalculated', v_rows);

    SELECT coalesce(array_agg(review_id), '{}') INTO v_ids FROM external_teacher_review_votes WHERE user_id = v_user;
    DELETE FROM external_teacher_review_votes WHERE user_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('external_teacher_review_votes', 'deleted', v_rows);
    UPDATE external_teacher_reviews e
    SET score = coalesce((SELECT sum(v.value) FROM external_teacher_review_votes v WHERE v.review_id = e.id), 0)
    WHERE e.id = ANY (v_ids);
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('external_teacher_reviews', 'score recalculated', v_rows);

    -- Subject links: what others see now (SubjectLinkViews.shown) stays, the rest goes.
    SELECT coalesce(array_agg(l.id), '{}') INTO v_keep_links FROM subject_links l
    WHERE l.owner_id = v_user AND l.visibility <> 'PRIVATE' AND l.hidden_at IS NULL
      AND EXISTS (SELECT 1 FROM subject_link_revisions r WHERE r.link_id = l.id AND r.status = 'APPROVED');
    SELECT coalesce(array_agg(l.id), '{}') INTO v_drop_links FROM subject_links l
    WHERE l.owner_id = v_user AND NOT (l.id = ANY (v_keep_links));

    -- As SubjectLinkService.delete: open cases withdrawn, reports removed; revisions, votes and pins cascade.
    UPDATE moderation_cases SET status = 'WITHDRAWN', resolved_at = v_now
    WHERE target_type = 'SUBJECT_RESOURCE' AND status = 'OPEN'
      AND target_id IN (SELECT r.id FROM subject_link_revisions r WHERE r.link_id = ANY (v_drop_links));
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('moderation_cases', 'withdrawn (deleted links)', v_rows);
    DELETE FROM moderation_reports
    WHERE target_type = 'SUBJECT_RESOURCE'
      AND target_id IN (SELECT r.id FROM subject_link_revisions r WHERE r.link_id = ANY (v_drop_links));
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('moderation_reports', 'deleted (deleted links)', v_rows);
    DELETE FROM subject_links WHERE id = ANY (v_drop_links);
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('subject_links', 'deleted', v_rows);

    -- Kept links: a pending revision is withdrawn (as ModerationService.withdraw), the row takes the shown
    -- content instead of an unpublished draft, and the owner becomes the placeholder.
    SELECT coalesce(array_agg(r.id), '{}') INTO v_ids FROM subject_link_revisions r
    WHERE r.link_id = ANY (v_keep_links) AND r.status = 'PENDING';
    UPDATE subject_link_revisions SET status = 'WITHDRAWN', decided_at = v_now WHERE id = ANY (v_ids);
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('subject_link_revisions', 'withdrawn (kept links)', v_rows);
    UPDATE moderation_cases SET status = 'WITHDRAWN', resolved_at = v_now
    WHERE target_type = 'SUBJECT_RESOURCE' AND status = 'OPEN' AND target_id = ANY (v_ids);
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('moderation_cases', 'withdrawn (kept links)', v_rows);
    UPDATE subject_links l
    SET owner_id = v_placeholder, category = a.category, url = a.url, normalized_url = a.normalized_url,
        title = a.title, visibility = a.visibility, flow_id = a.flow_id
    FROM (
        SELECT DISTINCT ON (r.link_id) r.link_id, r.category, r.url, r.normalized_url, r.title, r.visibility, r.flow_id
        FROM subject_link_revisions r
        WHERE r.link_id = ANY (v_keep_links) AND r.status = 'APPROVED'
        ORDER BY r.link_id, r.number DESC
    ) a
    WHERE l.id = a.link_id;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('subject_links', 'moved to placeholder', v_rows);
    -- A FLOW link is labelled with its flow name from the owner's schedule (SubjectLinkViews.Labels):
    -- the placeholder keeps only those flows.
    INSERT INTO user_subject_flows (user_id, subject_id, period_key, flow_id, group_name, type_id, last_seen)
    SELECT v_placeholder, f.subject_id, f.period_key, f.flow_id, f.group_name, f.type_id, f.last_seen
    FROM user_subject_flows f
    JOIN subject_links l ON l.subject_id = f.subject_id AND l.period_key = f.period_key AND l.flow_id = f.flow_id
    WHERE f.user_id = v_user AND l.id = ANY (v_keep_links)
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('user_subject_flows', 'copied to placeholder', v_rows);

    -- Teacher reviews: what others see now (TeacherReviewViews.shown) stays, the rest goes.
    SELECT coalesce(array_agg(t.id), '{}') INTO v_keep_reviews FROM teacher_reviews t
    WHERE t.author_id = v_user AND t.hidden_at IS NULL
      AND EXISTS (SELECT 1 FROM teacher_review_revisions r WHERE r.review_id = t.id AND r.status = 'APPROVED');
    SELECT coalesce(array_agg(t.id), '{}') INTO v_drop_reviews FROM teacher_reviews t
    WHERE t.author_id = v_user AND NOT (t.id = ANY (v_keep_reviews));

    -- As TeacherReviewService.delete: open cases withdrawn, reports removed; revisions, votes and flows cascade.
    UPDATE moderation_cases SET status = 'WITHDRAWN', resolved_at = v_now
    WHERE target_type = 'TEACHER_REVIEW' AND status = 'OPEN'
      AND target_id IN (SELECT r.id FROM teacher_review_revisions r WHERE r.review_id = ANY (v_drop_reviews));
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('moderation_cases', 'withdrawn (deleted reviews)', v_rows);
    DELETE FROM moderation_reports
    WHERE target_type = 'TEACHER_REVIEW'
      AND target_id IN (SELECT r.id FROM teacher_review_revisions r WHERE r.review_id = ANY (v_drop_reviews));
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('moderation_reports', 'deleted (deleted reviews)', v_rows);
    DELETE FROM teacher_reviews WHERE id = ANY (v_drop_reviews);
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('teacher_reviews', 'deleted', v_rows);

    -- Kept reviews: as kept links, plus anonymous. The ISU check needs the author's ISU, so a pending check
    -- ends as unverified and its candidate flows go.
    SELECT coalesce(array_agg(r.id), '{}') INTO v_ids FROM teacher_review_revisions r
    WHERE r.review_id = ANY (v_keep_reviews) AND r.status = 'PENDING';
    UPDATE teacher_review_revisions SET status = 'WITHDRAWN', decided_at = v_now WHERE id = ANY (v_ids);
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('teacher_review_revisions', 'withdrawn (kept reviews)', v_rows);
    UPDATE moderation_cases SET status = 'WITHDRAWN', resolved_at = v_now
    WHERE target_type = 'TEACHER_REVIEW' AND status = 'OPEN' AND target_id = ANY (v_ids);
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('moderation_cases', 'withdrawn (kept reviews)', v_rows);
    UPDATE teacher_reviews t
    SET author_id = v_placeholder, anonymous = true, subject_title = a.subject_title, text = a.text,
        verification = CASE WHEN t.verification = 'PENDING' THEN 'UNVERIFIED' ELSE t.verification END,
        verification_due_at = NULL
    FROM (
        SELECT DISTINCT ON (r.review_id) r.review_id, r.subject_title, r.text
        FROM teacher_review_revisions r
        WHERE r.review_id = ANY (v_keep_reviews) AND r.status = 'APPROVED'
        ORDER BY r.review_id, r.number DESC
    ) a
    WHERE t.id = a.review_id;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('teacher_reviews', 'moved to placeholder', v_rows);
    DELETE FROM teacher_review_flows WHERE review_id = ANY (v_keep_reviews);
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('teacher_review_flows', 'deleted (kept reviews)', v_rows);

    -- Moderation history and the admin audit stay whole and name the placeholder.
    UPDATE moderation_reports SET reporter_id = v_placeholder WHERE reporter_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('moderation_reports', 'reporter moved to placeholder', v_rows);
    UPDATE moderation_decisions SET moderator_id = v_placeholder WHERE moderator_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('moderation_decisions', 'moderator moved to placeholder', v_rows);
    UPDATE user_restrictions SET revoked_by = v_placeholder WHERE revoked_by = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('user_restrictions', 'revoker moved to placeholder', v_rows);
    UPDATE moderation_settings SET updated_by = v_placeholder WHERE updated_by = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('moderation_settings', 'editor moved to placeholder', v_rows);
    UPDATE admin_audit SET actor_id = v_placeholder WHERE actor_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('admin_audit', 'actor moved to placeholder', v_rows);
    -- Role changes name their target as user:<ISU> (AdminUsersService).
    UPDATE admin_audit SET target = 'user:' || (-v_isu) WHERE target = 'user:' || v_isu;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('admin_audit', 'target moved to placeholder', v_rows);

    -- The rest of the account.
    DELETE FROM user_restrictions WHERE user_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('user_restrictions', 'deleted', v_rows);
    DELETE FROM user_roles WHERE user_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('user_roles', 'deleted', v_rows);
    DELETE FROM subject_link_pins WHERE user_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('subject_link_pins', 'deleted', v_rows);
    DELETE FROM user_subject_flows WHERE user_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('user_subject_flows', 'deleted', v_rows);
    DELETE FROM friendships WHERE requester_id = v_user OR addressee_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('friendships', 'deleted', v_rows);
    DELETE FROM devices WHERE user_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('devices', 'deleted', v_rows);
    DELETE FROM sport_auto_sign_entries WHERE user_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('sport_auto_sign_entries', 'deleted', v_rows);
    DELETE FROM sport_free_sign_entries WHERE user_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('sport_free_sign_entries', 'deleted', v_rows);
    DELETE FROM user_sport_lessons WHERE user_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('user_sport_lessons', 'deleted', v_rows);
    DELETE FROM web_sessions WHERE user_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('web_sessions', 'deleted', v_rows);
    DELETE FROM web_login_challenges WHERE approved_by = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('web_login_challenges', 'deleted', v_rows);
    -- Lessons are keyed by ISU without a foreign key.
    DELETE FROM lessons WHERE user_isu = v_isu;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('lessons', 'deleted', v_rows);
    DELETE FROM user_groups WHERE user_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('user_groups', 'deleted', v_rows);
    DELETE FROM user_settings WHERE user_id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('user_settings', 'deleted', v_rows);
    -- app_settings, service_credentials and teacher_summaries set their editor to NULL themselves.
    -- Any reference this script does not know about fails here and rolls everything back.
    DELETE FROM users WHERE id = v_user;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    INSERT INTO account_deletion_report (table_name, action, affected) VALUES ('users', 'deleted', v_rows);
END
$deletion$;

SELECT table_name, action, affected FROM account_deletion_report ORDER BY step;

DROP TABLE account_deletion_report;

COMMIT;
