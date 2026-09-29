-- Manual rollback for V5__department_and_cost_center.sql (Issue #19). NEVER run by Flyway.
--
-- WARNING: business-data rollback is unsupported once departments or cost centers exist. This
-- script deletes every department and cost center. Their audit and outbox records remain (audit
-- is append-only) and would then reference removed resources.
--
-- The V4 platform operation-name checks are unaffected. Run in one transaction, then remove the V5
-- row from flyway_schema_history only if the application is also rolled back.
BEGIN;

DROP TRIGGER IF EXISTS site_period_covers_units ON tenant.site;
DROP TABLE IF EXISTS tenant.cost_center;
DROP TABLE IF EXISTS tenant.department;
DROP FUNCTION IF EXISTS tenant.site_period_covers_units();
DROP FUNCTION IF EXISTS tenant.site_unit_period_within_site();
DROP FUNCTION IF EXISTS tenant.reject_site_unit_parent_change();

COMMIT;
