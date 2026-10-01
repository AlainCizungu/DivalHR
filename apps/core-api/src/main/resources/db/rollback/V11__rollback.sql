-- Manual rollback for V11__access_review_indexes.sql (MVP-012B, Issue #37). NEVER run by Flyway.
--
-- Prefer rolling back only the application: the indexes are harmless to earlier versions. This
-- script drops the two review indexes and restores the V10 schema exactly; no data is touched.
-- Run in one transaction, then remove the V11 row from flyway_schema_history only if the
-- application is also rolled back.
BEGIN;

DROP INDEX identity.tenant_membership_review_order;
DROP INDEX identity.tenant_membership_review_role;

COMMIT;
