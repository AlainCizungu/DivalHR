-- Manual rollback for V3__legal_entity_and_site.sql. NEVER run by Flyway.
--
-- WARNING: business-data rollback is unsupported once legal entities or sites exist. This script
-- deletes every legal entity and site. Their audit and outbox records remain (audit is
-- append-only) and would then reference removed resources.
--
-- The widened platform check constraints (idempotency_operation_format, audit_action_format) are
-- deliberately NOT restored to the V2 expressions: they are a backward-compatible platform-schema
-- evolution, and committed hierarchy rows ("legal-entity.create") would violate the old ones.
--
-- After running it, delete the V3 row from flyway_schema_history.
DROP TRIGGER IF EXISTS legal_entity_period_covers_sites ON tenant.legal_entity;
DROP TRIGGER IF EXISTS site_period_within_legal_entity ON tenant.site;
DROP TRIGGER IF EXISTS site_ownership_immutable ON tenant.site;
DROP TRIGGER IF EXISTS legal_entity_ownership_immutable ON tenant.legal_entity;
DROP TABLE IF EXISTS tenant.site;
DROP TABLE IF EXISTS tenant.legal_entity;
DROP FUNCTION IF EXISTS tenant.legal_entity_period_covers_sites();
DROP FUNCTION IF EXISTS tenant.site_period_within_legal_entity();
DROP FUNCTION IF EXISTS tenant.reject_ownership_change();
