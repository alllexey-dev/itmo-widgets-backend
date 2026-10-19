-- The push categories the user switched off on a device (PushCategory names, comma-separated, empty for none).
-- Expand-only: older images ignore the column and their inserts get the default, so a rollback stays image-only.

-- A constant default changes only the catalog: no table rewrite.
ALTER TABLE devices
    ADD COLUMN muted_push_categories varchar(64) NOT NULL DEFAULT '';
