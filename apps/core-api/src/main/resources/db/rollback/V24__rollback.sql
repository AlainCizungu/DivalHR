-- Manual rollback for V24__leave_approval_substitution.sql (MVP-041G, Issue #97). NEVER run by
-- Flyway.
--
-- Prefer rolling back only the application. This script restores the V23 schema exactly
-- (signature-tested: the function body below is V22's, verbatim, unchanged by V23), but it REFUSES
-- once any substitute appointment, coverage row or revocation exists or any decision was taken
-- with the MANAGER_SUBSTITUTE authority: that evidence must never be erased or reinterpreted by a schema
-- rollback, so there is no override flag. Every V23 request, decision, cancellation, amendment and
-- withdrawal is kept unchanged (dropping delegated_from_manager_employee_id removes only a column
-- that is null on every remaining decision).
--
-- It does NOT edit flyway_schema_history: removing the V24 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.leave_request_decision, people.leave_approval_substitution,
    people.leave_approval_substitution_coverage, people.leave_approval_substitution_revocation
    IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.leave_approval_substitution)
        OR EXISTS (SELECT 1 FROM people.leave_approval_substitution_coverage)
        OR EXISTS (SELECT 1 FROM people.leave_approval_substitution_revocation)
        OR EXISTS (SELECT 1 FROM people.leave_request_decision
            WHERE decision_authority = 'MANAGER_SUBSTITUTE'
                OR delegated_from_manager_employee_id IS NOT NULL) THEN
        RAISE EXCEPTION 'V24 rollback refused: leave approval substitutes or substitute decisions exist';
    END IF;
END
$$;
DROP TABLE people.leave_approval_substitution_coverage;
DROP TABLE people.leave_approval_substitution_revocation;
DROP TABLE people.leave_approval_substitution;
DROP FUNCTION people.leave_approval_substitution_revocation_uncovered();
DROP FUNCTION people.leave_approval_substitution_covered();
DROP FUNCTION people.leave_approval_substitution_coverage_no_truncate();
DROP FUNCTION people.leave_approval_substitution_coverage_guard();
DROP FUNCTION people.leave_approval_substitution_revocation_no_truncate();
DROP FUNCTION people.leave_approval_substitution_revocation_immutable();
DROP FUNCTION people.leave_approval_substitution_no_truncate();
DROP FUNCTION people.leave_approval_substitution_immutable();
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
    IF v_state IS DISTINCT FROM NEW.outcome OR v_route IS DISTINCT FROM NEW.approval_route THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_decision_consistent',
            MESSAGE = 'a decision matches its request state and its policy route';
    END IF;
    RETURN NULL;
END
$fn$;
ALTER TABLE people.leave_request_decision
    DROP CONSTRAINT leave_request_decision_authority_shape,
    ADD CONSTRAINT leave_request_decision_authority_shape CHECK (
        (approval_route = 'MANAGER' AND decision_authority = 'MANAGER'
            AND manager_employee_id IS NOT NULL)
        OR (approval_route = 'TENANT_ADMIN' AND decision_authority = 'TENANT_ADMIN'
            AND manager_employee_id IS NULL)
        OR (approval_route = 'MANAGER' AND decision_authority = 'TENANT_ADMIN_OVERRIDE'
            AND manager_employee_id IS NULL)),
    DROP CONSTRAINT leave_request_decision_delegated_from,
    DROP COLUMN delegated_from_manager_employee_id;
COMMIT;
