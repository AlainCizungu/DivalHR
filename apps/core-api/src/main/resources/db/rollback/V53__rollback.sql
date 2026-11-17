-- ---------------------------------------------------------------------------------------------
-- Manual rollback for V53__salary_advance_partner_handoff.sql (MVP-120B, Issue #206). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V52 schema exactly, but it REFUSES once any
-- MVP-120B row exists: partner handoffs, consent evidence, delivery events and attempts, acknowledgements, callback
-- replay evidence and review events are employee financial-sharing evidence. None of it is erased by a schema
-- rollback; there is no override, no deletion and no retention decision. V53 seeded nothing and changed no earlier
-- table (it only added a trigger to payroll.salary_advance_request, removed below).
--
-- It does NOT edit flyway_schema_history: removing the V53 row is a separate, controlled operator step, taken only
-- when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE payroll.salary_advance_handoff, payroll.salary_advance_handoff_consent,
    payroll.salary_advance_handoff_event, payroll.salary_advance_handoff_attempt, payroll.salary_advance_handoff_ack,
    payroll.salary_advance_handoff_callback, payroll.salary_advance_handoff_review_event IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.salary_advance_handoff)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_handoff_consent)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_handoff_event)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_handoff_attempt)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_handoff_ack)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_handoff_callback)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_handoff_review_event) THEN
        RAISE EXCEPTION 'V53 rollback refused: partner handoff, consent, delivery, callback or review rows exist';
    END IF;
END
$$;

DROP TRIGGER payroll_salary_advance_request_handoff_guard ON payroll.salary_advance_request;
DROP TABLE payroll.salary_advance_handoff_review_event;
DROP TABLE payroll.salary_advance_handoff_callback;
DROP TABLE payroll.salary_advance_handoff_ack;
DROP TABLE payroll.salary_advance_handoff_attempt;
DROP TABLE payroll.salary_advance_handoff_event;
DROP TABLE payroll.salary_advance_handoff_consent;
DROP TABLE payroll.salary_advance_handoff;
DROP FUNCTION payroll.salary_advance_request_handoff_guard();
DROP FUNCTION payroll.salary_advance_handoff_complete();
DROP FUNCTION payroll.salary_advance_handoff_review_event_guard();
DROP FUNCTION payroll.salary_advance_handoff_callback_guard();
DROP FUNCTION payroll.salary_advance_handoff_ack_guard();
DROP FUNCTION payroll.salary_advance_handoff_attempt_guard();
DROP FUNCTION payroll.salary_advance_handoff_event_guard();
DROP FUNCTION payroll.salary_advance_handoff_consent_guard();
DROP FUNCTION payroll.salary_advance_handoff_guard();
COMMIT;
