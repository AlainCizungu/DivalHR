-- Manual rollback for V2__organization_idempotency_audit_outbox.sql. NEVER run by Flyway.
-- Only safe before any real data exists (dropping the audit table destroys audit history).
-- After running it, delete the V2 row from flyway_schema_history.
DROP TABLE IF EXISTS platform.outbox_event;
DROP TRIGGER IF EXISTS audit_event_no_truncate ON platform.audit_event;
DROP TRIGGER IF EXISTS audit_event_no_update_delete ON platform.audit_event;
DROP TABLE IF EXISTS platform.audit_event;
DROP FUNCTION IF EXISTS platform.audit_event_append_only();
DROP TABLE IF EXISTS platform.idempotency_record;
DROP FUNCTION IF EXISTS platform.idempotency_require_completed();
DROP TABLE IF EXISTS tenant.organization_currency;
DROP TABLE IF EXISTS tenant.organization;
