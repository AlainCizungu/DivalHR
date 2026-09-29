-- Manual rollback of V4 (Issue #17). Never run by Flyway.
-- Restores the V3 expressions, which are a strict superset of V4, so no existing row can violate
-- them: the rollback is non-destructive. Run in one transaction, then remove the V4 row from
-- flyway_schema_history only if the application is also rolled back.
BEGIN;

ALTER TABLE platform.idempotency_record
    DROP CONSTRAINT idempotency_operation_format,
    ADD CONSTRAINT idempotency_operation_format
        CHECK (operation ~ '^[a-z]+(-[a-z]+)*(\.[a-z-]+)+$');

ALTER TABLE platform.audit_event
    DROP CONSTRAINT audit_action_format,
    ADD CONSTRAINT audit_action_format
        CHECK (action ~ '^[a-z]+(-[a-z]+)*(\.[a-z-]+)+$');

COMMIT;
