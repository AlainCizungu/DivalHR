-- Manual rollback for V7__team.sql (Issue #23). NEVER run by Flyway.
--
-- Prefer rolling back the APPLICATION only: V7 is additive and the previous application version
-- works unchanged against it, so no schema rollback is needed to undo a release.
--
-- WARNING: business-data rollback is unsupported once teams exist. This script permanently
-- deletes every team. Departments, cost centers and everything above them are kept unchanged.
-- Audit and outbox records of team.create remain (audit is append-only) and would then
-- reference removed teams.
--
-- Idempotency records of team.create are purged in the same transaction: otherwise an unexpired
-- key would replay a response for a team that no longer exists. Run in one transaction, then
-- remove the V7 row from flyway_schema_history only if the application is also rolled back.
BEGIN;

DELETE FROM platform.idempotency_record WHERE operation = 'team.create';

DROP TRIGGER IF EXISTS department_period_covers_teams ON tenant.department;
DROP TRIGGER IF EXISTS cost_center_period_covers_teams ON tenant.cost_center;
DROP TABLE IF EXISTS tenant.team;
DROP FUNCTION IF EXISTS tenant.site_unit_period_covers_teams();
DROP FUNCTION IF EXISTS tenant.team_period_within_parent();
DROP FUNCTION IF EXISTS tenant.reject_team_parent_change();
ALTER TABLE tenant.cost_center DROP CONSTRAINT IF EXISTS cost_center_tenant_site_id_unique;
ALTER TABLE tenant.department DROP CONSTRAINT IF EXISTS department_tenant_site_id_unique;

COMMIT;
