-- When POST /api/users/me/installs/{instanceId}/sign-out last dropped an app install's push registration
-- (InstallInfo.signedOutAt, docs/contracts/connections.md#devices). Null for an install never signed out this way;
-- cleared when the install registers a push token again.
--
-- Expand-only: a nullable column without a default; images before V30 neither read nor write it, so a rollback stays
-- image-only, and their upsert of client_instances leaves it alone.
ALTER TABLE client_instances ADD COLUMN push_signed_out_at timestamp(6) with time zone;
