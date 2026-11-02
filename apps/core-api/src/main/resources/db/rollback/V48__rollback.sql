-- ---------------------------------------------------------------------------------------------
-- Manual rollback for V48__payroll_payment_submissions.sql (MVP-095B, Issue #187). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V47 schema exactly, but it REFUSES once any
-- MVP-095B evidence exists: a payment submission or an attempt row is the record of what was handed to a provider
-- connector and with what outcome. None of it is erased by a schema rollback; there is no override, no deletion and
-- no retention decision. V48 seeded nothing. Besides its own tables, triggers and functions, the script restores the
-- V47 body of payroll.payment_batch_blocks_void() and removes the two cancel guards V48 added; V48 changed no other
-- earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V48 row is a separate, controlled operator step, taken only
-- when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
-- ---------------------------------------------------------------------------------------------
BEGIN;
LOCK TABLE payroll.payment_submission, payroll.payment_submission_attempt IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.payment_submission)
        OR EXISTS (SELECT 1 FROM payroll.payment_submission_attempt) THEN
        RAISE EXCEPTION 'V48 rollback refused: payment submission or attempt rows exist';
    END IF;
END
$$;

DROP TRIGGER payment_submission_blocks_cancel_evidence ON payroll.payment_batch_cancel;
DROP TRIGGER payment_submission_blocks_cancel ON payroll.payment_batch;
DROP FUNCTION payroll.payment_submission_blocks_cancel();

CREATE OR REPLACE FUNCTION payroll.payment_batch_blocks_void() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF NEW.status = 'VOIDED' AND EXISTS (
        SELECT 1 FROM payroll.payment_batch b
         WHERE b.tenant_id = OLD.tenant_id AND b.package_id = OLD.id AND b.status = 'PREPARED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_payment_batch_blocks_void',
            MESSAGE = 'a payroll package with a PREPARED payment batch cannot be voided; cancel the batch first';
    END IF;
    RETURN NEW;
END
$fn$;

DROP TABLE payroll.payment_submission_attempt;
DROP TABLE payroll.payment_submission;
DROP FUNCTION payroll.payment_submission_attempt_exact();
DROP FUNCTION payroll.payment_submission_exact();
DROP FUNCTION payroll.payment_submission_check(uuid, uuid);
DROP FUNCTION payroll.payment_submission_attempt_guard();
DROP FUNCTION payroll.payment_submission_guard();
DROP FUNCTION payroll.payment_submission_transition_ok(text, text);
DROP FUNCTION payroll.payment_submission_immutable();
COMMIT;
