-- Manual rollback for V23__approved_leave_withdrawal.sql (MVP-041F, Issue #95). NEVER run by
-- Flyway.
--
-- Prefer rolling back only the application. This script restores the V22 schema exactly
-- (signature-tested: the function bodies below are V22's, verbatim), but it REFUSES once any
-- withdrawal exists or any request is WITHDRAWN: that evidence must never be erased or
-- reinterpreted by a schema rollback, so there is no override flag. Every V22 decision,
-- cancellation and amendment is kept unchanged.
--
-- It does NOT edit flyway_schema_history: removing the V23 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.leave_request, people.leave_request_decision, people.leave_request_cancellation,
    people.leave_request_amendment, people.leave_request_withdrawal IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.leave_request_withdrawal)
        OR EXISTS (SELECT 1 FROM people.leave_request WHERE state = 'WITHDRAWN') THEN
        RAISE EXCEPTION 'V23 rollback refused: leave withdrawals exist';
    END IF;
END
$$;
DROP TABLE people.leave_request_withdrawal;
DROP FUNCTION people.leave_request_withdrawal_consistent();
DROP FUNCTION people.leave_request_withdrawal_no_truncate();
DROP FUNCTION people.leave_request_withdrawal_immutable();
CREATE OR REPLACE FUNCTION people.leave_request_decided() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_state     text;
    v_decision  text;
    v_cancelled boolean;
    v_amended   boolean;
    v_ok        boolean;
BEGIN
    SELECT r.state INTO v_state FROM people.leave_request r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.id;
    SELECT d.outcome INTO v_decision FROM people.leave_request_decision d
        WHERE d.tenant_id = NEW.tenant_id AND d.request_id = NEW.id;
    v_cancelled := EXISTS (SELECT 1 FROM people.leave_request_cancellation c
        WHERE c.tenant_id = NEW.tenant_id AND c.request_id = NEW.id);
    v_amended := EXISTS (SELECT 1 FROM people.leave_request_amendment a
        WHERE a.tenant_id = NEW.tenant_id AND a.original_request_id = NEW.id);
    IF v_state = 'PENDING' THEN
        v_ok := v_decision IS NULL AND NOT v_cancelled AND NOT v_amended;
    ELSIF v_state = 'CANCELLED' THEN
        v_ok := v_decision IS NULL AND v_cancelled AND NOT v_amended;
    ELSIF v_state = 'AMENDED' THEN
        v_ok := v_decision IS NULL AND NOT v_cancelled AND v_amended;
    ELSE
        v_ok := v_decision IS NOT DISTINCT FROM v_state AND NOT v_cancelled AND NOT v_amended;
    END IF;
    IF NOT v_ok THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_decided',
            MESSAGE = 'a leave request commits with exactly its matching terminal evidence';
    END IF;
    RETURN NULL;
END
$fn$;
CREATE OR REPLACE FUNCTION people.leave_request_transition() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF OLD.state <> 'PENDING' OR NEW.state NOT IN ('APPROVED', 'REJECTED', 'CANCELLED', 'AMENDED')
        OR (to_jsonb(NEW) - 'state') IS DISTINCT FROM (to_jsonb(OLD) - 'state') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_transition',
            MESSAGE = 'a leave request changes once, from PENDING to APPROVED, REJECTED, CANCELLED or AMENDED';
    END IF;
    RETURN NEW;
END
$fn$;
ALTER TABLE people.leave_request
    DROP CONSTRAINT leave_request_state_valid,
    ADD CONSTRAINT leave_request_state_valid
        CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'AMENDED'));
COMMIT;
