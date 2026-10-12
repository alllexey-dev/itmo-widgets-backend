-- Placeholders of deleted accounts no longer encode the account's ISU. Until now a deletion created the placeholder
-- with isu = -<ISU>, so anyone who saw a kept link's author could read the ISU back. From now on the placeholder takes
-- its negative ISU from deleted_user_isu_seq. This script renumbers the existing placeholders (every user with a
-- negative ISU) from the sequence and rewrites their admin audit targets user:-<ISU> the same way.
--
-- Backend 1.9.1 still creates placeholders as -<ISU> with ON CONFLICT DO NOTHING, so a rollback stays image-only; a
-- deletion made by that image is renumbered only by hand.

CREATE SEQUENCE deleted_user_isu_seq AS integer;

-- One placeholder at a time, without a temporary table: the application role has no TEMP privilege. A new number is
-- taken only when no user holds it at that moment, so no UPDATE hits uq_users_isu.
DO $renumber$
DECLARE
    v_placeholder record;
    v_isu integer;
BEGIN
    FOR v_placeholder IN SELECT id, isu FROM users WHERE isu < 0 ORDER BY created_at, id LOOP
        LOOP
            v_isu := -nextval('deleted_user_isu_seq')::integer;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM users WHERE isu = v_isu);
        END LOOP;
        UPDATE users SET isu = v_isu WHERE id = v_placeholder.id;
        UPDATE admin_audit SET target = 'user:' || v_isu WHERE target = 'user:' || v_placeholder.isu;
    END LOOP;
END
$renumber$;
