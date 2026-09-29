-- Manual rollback for V6__region_and_site_region.sql (Issue #21). NEVER run by Flyway.
--
-- Prefer rolling back the APPLICATION only: V6 is additive and the previous application version
-- works unchanged against it (unassigned and assigned sites alike), so no schema rollback is
-- needed to undo a release.
--
-- WARNING: business-data rollback is unsupported once regions exist. This script permanently
-- deletes every region and every site-region assignment (tenant.site.region_id is dropped; the
-- sites themselves, their departments and cost centers are kept, all as sites without a region).
-- Audit and outbox records of region.create and site.region.assign remain (audit is append-only)
-- and would then reference removed regions and assignments.
--
-- Idempotency records of those operations must be purged in the same transaction: otherwise an
-- unexpired key would replay a response for a region or an assignment that no longer exists.
-- Run in one transaction, then remove the V6 row from flyway_schema_history only if the
-- application is also rolled back.
BEGIN;

DELETE FROM platform.idempotency_record
 WHERE operation IN ('region.create', 'site.region.assign');

DROP TRIGGER IF EXISTS site_period_within_region ON tenant.site;
DROP TRIGGER IF EXISTS site_region_assigned_once ON tenant.site;
DROP INDEX IF EXISTS tenant.site_tenant_region;
ALTER TABLE tenant.site DROP CONSTRAINT IF EXISTS site_region_same_tenant_and_legal_entity;
ALTER TABLE tenant.site DROP COLUMN IF EXISTS region_id;
DROP TRIGGER IF EXISTS legal_entity_period_covers_regions ON tenant.legal_entity;
DROP TABLE IF EXISTS tenant.region;
DROP FUNCTION IF EXISTS tenant.region_period_covers_sites();
DROP FUNCTION IF EXISTS tenant.site_period_within_region();
DROP FUNCTION IF EXISTS tenant.site_region_assigned_once();
DROP FUNCTION IF EXISTS tenant.legal_entity_period_covers_regions();
DROP FUNCTION IF EXISTS tenant.region_period_within_legal_entity();
DROP FUNCTION IF EXISTS tenant.reject_legal_entity_parent_change();

COMMIT;
