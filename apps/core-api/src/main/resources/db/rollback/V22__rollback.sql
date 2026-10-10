-- Manual rollback for V22__leave_request_amendment_and_routing_exception.sql (MVP-041D/E, Issue
-- #92). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V21 schema exactly
-- (signature-tested: the function bodies below are V21's and V20's, verbatim), but it REFUSES once
-- any amendment exists, any request is AMENDED or any decision was taken with the
-- TENANT_ADMIN_OVERRIDE authority: that evidence must never be erased or reinterpreted by a schema
-- rollback, so there is no override flag. Existing decisions and cancellations are kept unchanged
-- (dropping decision_authority removes only the backfilled copy of each decision's route).
--
-- It does NOT edit flyway_schema_history: removing the V22 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.leave_request, people.leave_request_decision, people.leave_request_cancellation,
    people.leave_request_amendment IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.leave_request_amendment)
        OR EXISTS (SELECT 1 FROM people.leave_request WHERE state = 'AMENDED')
        OR EXISTS (SELECT 1 FROM people.leave_request_decision
            WHERE decision_authority = 'TENANT_ADMIN_OVERRIDE') THEN
        RAISE EXCEPTION 'V22 rollback refused: leave amendments or routing-exception decisions exist';
    END IF;
END
$$;
DROP TABLE people.leave_request_amendment;
DROP FUNCTION people.leave_request_amendment_consistent();
DROP FUNCTION people.leave_request_amendment_no_truncate();
DROP FUNCTION people.leave_request_amendment_immutable();
CREATE OR REPLACE FUNCTION people.leave_request_decided() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_state     text;
    v_decision  text;
    v_cancelled boolean;
    v_ok        boolean;
BEGIN
    SELECT r.state INTO v_state FROM people.leave_request r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.id;
    SELECT d.outcome INTO v_decision FROM people.leave_request_decision d
        WHERE d.tenant_id = NEW.tenant_id AND d.request_id = NEW.id;
    v_cancelled := EXISTS (SELECT 1 FROM people.leave_request_cancellation c
        WHERE c.tenant_id = NEW.tenant_id AND c.request_id = NEW.id);
    IF v_state = 'PENDING' THEN
        v_ok := v_decision IS NULL AND NOT v_cancelled;
    ELSIF v_state = 'CANCELLED' THEN
        v_ok := v_decision IS NULL AND v_cancelled;
    ELSE
        v_ok := v_decision IS NOT DISTINCT FROM v_state AND NOT v_cancelled;
    END IF;
    IF NOT v_ok THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_decided',
            MESSAGE = 'a leave request commits with exactly its matching terminal evidence';
    END IF;
    RETURN NULL;
END
$fn$;
CREATE OR REPLACE FUNCTION people.leave_request_decision_consistent() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_state text;
    v_route text;
BEGIN
    SELECT r.state, v.approval_route INTO v_state, v_route
        FROM people.leave_request r
        JOIN people.leave_policy_version v
            ON v.tenant_id = r.tenant_id AND v.id = r.policy_version_id
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.request_id;
    IF v_state IS DISTINCT FROM NEW.outcome
        OR v_route IS DISTINCT FROM NEW.approval_route
        OR (NEW.approval_route = 'MANAGER') <> (NEW.manager_employee_id IS NOT NULL) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_decision_consistent',
            MESSAGE = 'a decision matches its request state and its policy route';
    END IF;
    RETURN NULL;
END
$fn$;
ALTER TABLE people.leave_request_decision
    DROP CONSTRAINT leave_request_decision_authority_shape,
    ADD CONSTRAINT leave_request_decision_route_shape CHECK (
        (approval_route = 'MANAGER' AND manager_employee_id IS NOT NULL)
        OR (approval_route = 'TENANT_ADMIN' AND manager_employee_id IS NULL)),
    DROP COLUMN decision_authority;
CREATE OR REPLACE FUNCTION people.leave_request_transition() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF OLD.state <> 'PENDING' OR NEW.state NOT IN ('APPROVED', 'REJECTED', 'CANCELLED')
        OR (to_jsonb(NEW) - 'state') IS DISTINCT FROM (to_jsonb(OLD) - 'state') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_transition',
            MESSAGE = 'a leave request changes once, from PENDING to APPROVED, REJECTED or CANCELLED';
    END IF;
    RETURN NEW;
END
$fn$;
ALTER TABLE people.leave_request
    DROP CONSTRAINT leave_request_state_valid,
    ADD CONSTRAINT leave_request_state_valid
        CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'));
COMMIT;
