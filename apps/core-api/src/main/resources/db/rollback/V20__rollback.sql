-- Manual rollback for V20__leave_request_decision.sql (MVP-041B, Issue #89). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V19 schema exactly
-- (signature-tested), but it REFUSES once any decision exists or any request has left PENDING:
-- decision evidence must never be erased by a schema rollback, so there is no override flag.
--
-- It does NOT edit flyway_schema_history: removing the V20 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;

LOCK TABLE people.leave_request, people.leave_request_decision IN ACCESS EXCLUSIVE MODE;

DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.leave_request_decision)
        OR EXISTS (SELECT 1 FROM people.leave_request WHERE state <> 'PENDING') THEN
        RAISE EXCEPTION 'V20 rollback refused: leave decisions exist';
    END IF;
END
$$;

DROP TABLE people.leave_request_decision;

DROP TRIGGER leave_request_decided ON people.leave_request;
DROP TRIGGER leave_request_transition ON people.leave_request;
DROP TRIGGER leave_request_immutable ON people.leave_request;
CREATE TRIGGER leave_request_immutable
    BEFORE UPDATE OR DELETE ON people.leave_request
    FOR EACH ROW EXECUTE FUNCTION people.leave_request_immutable();

DROP INDEX people.leave_request_employment_pending;
DROP INDEX people.leave_request_pending_queue;

ALTER TABLE people.leave_request
    DROP CONSTRAINT leave_request_no_overlap,
    ADD CONSTRAINT leave_request_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, employee_id WITH =,
        daterange(start_date, end_date, '[]') WITH &&) WHERE (state = 'PENDING');

ALTER TABLE people.leave_request
    DROP CONSTRAINT leave_request_state_valid,
    ADD CONSTRAINT leave_request_state_valid CHECK (state = 'PENDING');

DROP FUNCTION people.leave_request_decision_consistent();
DROP FUNCTION people.leave_request_decided();
DROP FUNCTION people.leave_request_decision_no_truncate();
DROP FUNCTION people.leave_request_decision_immutable();
DROP FUNCTION people.leave_reason_valid(text);
DROP FUNCTION people.leave_request_transition();

COMMIT;
