-- ---------------------------------------------------------------------------------------------
-- Manual rollback for V52__salary_advance_requests.sql (MVP-120A, Issue #204). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V51 schema exactly, but it REFUSES once
-- any MVP-120A business row exists: salary-advance policies, requests, consent evidence, employer decisions and
-- lifecycle history are employee financial evidence. None of it is erased by a schema rollback; there is no
-- override, no deletion and no retention decision. V52 seeded nothing and changed no earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V52 row is a separate, controlled operator step, taken only
-- when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE payroll.salary_advance_policy, payroll.salary_advance_request, payroll.salary_advance_consent,
    payroll.salary_advance_employer_decision, payroll.salary_advance_request_event IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.salary_advance_policy)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_request)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_consent)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_employer_decision)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_request_event) THEN
        RAISE EXCEPTION 'V52 rollback refused: salary-advance policy, request, consent, decision or history rows exist';
    END IF;
END
$$;

DROP TABLE payroll.salary_advance_request_event;
DROP TABLE payroll.salary_advance_employer_decision;
DROP TABLE payroll.salary_advance_consent;
DROP TABLE payroll.salary_advance_request;
DROP TABLE payroll.salary_advance_policy;
DROP FUNCTION payroll.salary_advance_request_complete();
DROP FUNCTION payroll.salary_advance_request_guard();
DROP FUNCTION payroll.salary_advance_event_guard();
DROP FUNCTION payroll.salary_advance_decision_guard();
DROP FUNCTION payroll.salary_advance_policy_guard();
DROP FUNCTION payroll.salary_advance_immutable();
COMMIT;
