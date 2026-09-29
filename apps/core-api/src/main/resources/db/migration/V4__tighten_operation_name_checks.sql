-- Issue #17: restore the approved strict operation-name grammar (post-merge review of PR #16).
-- V3 widened both checks with a suffix `(\.[a-z-]+)+` that admits malformed segments such as
-- `x.---`. Every segment must now be lower-case words joined by single hyphens.
-- Grammar (identical to com.divalhr.core.platform.operation.OperationName.GRAMMAR):
--   ^[a-z]+(-[a-z]+)*(\.[a-z]+(-[a-z]+)*)+$
--
-- Forward-only; V1-V3 are unchanged. Flyway runs this file in one PostgreSQL transaction: if the
-- pre-flight or either constraint replacement fails, neither change is committed.
-- The pre-flight never rewrites data and reports only counts, never row contents.
-- Manual rollback (restores the V3 superset): db/rollback/V4__rollback.sql.

DO $$
DECLARE
    bad_operations bigint;
    bad_actions bigint;
BEGIN
    SELECT count(*) INTO bad_operations
      FROM platform.idempotency_record
     WHERE operation !~ '^[a-z]+(-[a-z]+)*(\.[a-z]+(-[a-z]+)*)+$';
    SELECT count(*) INTO bad_actions
      FROM platform.audit_event
     WHERE action !~ '^[a-z]+(-[a-z]+)*(\.[a-z]+(-[a-z]+)*)+$';
    IF bad_operations > 0 OR bad_actions > 0 THEN
        RAISE EXCEPTION 'V4 pre-flight failed: % idempotency operation(s) and % audit action(s) do not follow the operation-name grammar',
            bad_operations, bad_actions;
    END IF;
END
$$;

ALTER TABLE platform.idempotency_record
    DROP CONSTRAINT idempotency_operation_format,
    ADD CONSTRAINT idempotency_operation_format
        CHECK (operation ~ '^[a-z]+(-[a-z]+)*(\.[a-z]+(-[a-z]+)*)+$');

ALTER TABLE platform.audit_event
    DROP CONSTRAINT audit_action_format,
    ADD CONSTRAINT audit_action_format
        CHECK (action ~ '^[a-z]+(-[a-z]+)*(\.[a-z]+(-[a-z]+)*)+$');
