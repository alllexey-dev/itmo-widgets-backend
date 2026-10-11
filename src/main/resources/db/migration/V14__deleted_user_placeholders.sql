-- Placeholders of deleted accounts no longer encode the account's ISU. Until now a deletion created the placeholder
-- with isu = -<ISU>, so anyone who saw a kept link's author could read the ISU back. From now on the placeholder takes
-- its negative ISU from deleted_user_isu_seq. This script renumbers the existing placeholders (every user with a
-- negative ISU) from the sequence and rewrites their admin audit targets user:-<ISU> the same way.
--
-- Backend 1.9.1 still creates placeholders as -<ISU> with ON CONFLICT DO NOTHING, so a rollback stays image-only; a
-- deletion made by that image is renumbered only by hand.

CREATE SEQUENCE deleted_user_isu_seq AS integer;

CREATE TEMP TABLE placeholder_isu (id uuid PRIMARY KEY, old_isu integer NOT NULL, new_isu integer NOT NULL) ON COMMIT DROP;

DO $renumber$
DECLARE
    v_placeholder record;
    v_isu integer;
BEGIN
    FOR v_placeholder IN SELECT id, isu FROM users WHERE isu < 0 ORDER BY created_at, id LOOP
        -- Every new number differs from every current one, so the single UPDATE below never hits uq_users_isu.
        LOOP
            v_isu := -nextval('deleted_user_isu_seq')::integer;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM users WHERE isu = v_isu);
        END LOOP;
        INSERT INTO placeholder_isu (id, old_isu, new_isu) VALUES (v_placeholder.id, v_placeholder.isu, v_isu);
    END LOOP;
END
$renumber$;

UPDATE users u SET isu = p.new_isu FROM placeholder_isu p WHERE u.id = p.id;
UPDATE admin_audit a SET target = 'user:' || p.new_isu FROM placeholder_isu p WHERE a.target = 'user:' || p.old_isu;

DROP TABLE placeholder_isu;
