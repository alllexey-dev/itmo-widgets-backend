-- The family_name and given_name claims of the ITMO.ID id token, stored by PUT /api/users/me/id-token. ITMO.ID's
-- `name` claim lacks one part, so `users.name` becomes «family_name given_name» when the token has both. Both parts
-- are public in UserData, like the name. Expand-only: nullable columns, no backfill; an account fills them on its
-- next id token. The previous image ignores them, so a rollback stays image-only. Account deletion drops the row
-- with them; its placeholder keeps nulls.

-- Nullable columns without defaults change only the catalog: no table rewrite.
ALTER TABLE users
    ADD COLUMN family_name text,
    ADD COLUMN given_name text;
