-- Manual rollback for V21__leave_request_cancellation.sql (MVP-041C, Issue #91). NEVER run by
-- Flyway.
--
-- Prefer rolling back only the application. This script restores the V20 schema exactly
-- (signature-tested: the function bodies below are V20's, verbatim), but it REFUSES once any
-- cancellation exists or any request is CANCELLED: cancellation evidence must never be erased by a
-- schema rollback, so there is no override flag.
--
-- It does NOT edit flyway_schema_history: removing the V21 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.leave_request, people.leave_request_decision,
    people.leave_request_cancellation IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.leave_request_cancellation)
        OR EXISTS (SELECT 1 FROM people.leave_request WHERE state = 'CANCELLED') THEN
        RAISE EXCEPTION 'V21 rollback refused: leave cancellations exist';
    END IF;
END
$$;
DROP TABLE people.leave_request_cancellation;
DROP FUNCTION people.leave_request_cancellation_consistent();
DROP FUNCTION people.leave_request_cancellation_no_truncate();
DROP FUNCTION people.leave_request_cancellation_immutable();
CREATE OR REPLACE FUNCTION people.leave_request_decided() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_state text;
BEGIN
    SELECT r.state INTO v_state FROM people.leave_request r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.id;
    IF v_state <> 'PENDING' AND NOT EXISTS (SELECT 1 FROM people.leave_request_decision d
        WHERE d.tenant_id = NEW.tenant_id AND d.request_id = NEW.id AND d.outcome = v_state) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_decided',
            MESSAGE = 'a decided leave request commits with its matching decision';
    END IF;
    RETURN NULL;
END
$fn$;
CREATE OR REPLACE FUNCTION people.leave_request_transition() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF OLD.state <> 'PENDING' OR NEW.state NOT IN ('APPROVED', 'REJECTED')
        OR (to_jsonb(NEW) - 'state') IS DISTINCT FROM (to_jsonb(OLD) - 'state') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_transition',
            MESSAGE = 'a leave request changes once, from PENDING to APPROVED or REJECTED';
    END IF;
    RETURN NEW;
END
$fn$;
ALTER TABLE people.leave_request
    DROP CONSTRAINT leave_request_state_valid,
    ADD CONSTRAINT leave_request_state_valid CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED'));
COMMIT;
