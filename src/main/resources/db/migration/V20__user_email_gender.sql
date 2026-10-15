-- The email and gender claims of the ITMO.ID id token, stored with name and picture by PUT /api/users/me/id-token.
-- Stored as ITMO.ID sends them (gender is `male` or `female`); gender is public in UserData for declension, email
-- stays with the owner. Expand-only: nullable columns, no backfill; an account fills them on its next id token. The
-- previous image ignores them, so a rollback stays image-only. Account deletion drops the row with them; its placeholder keeps nulls.

-- Nullable columns without defaults change only the catalog: no table rewrite.
ALTER TABLE users
    ADD COLUMN email text,
    ADD COLUMN gender text;
