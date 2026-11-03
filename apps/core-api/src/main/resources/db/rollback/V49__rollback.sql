-- ---------------------------------------------------------------------------------------------
-- Manual rollback for V49__payroll_payment_reconciliation.sql (MVP-095C, Issue #190). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V48 schema exactly, but it REFUSES once any
-- MVP-095C evidence exists: a reconciliation or an inquiry row is the record of what was asked of a provider connector
-- about an uncertain payment and with what authenticated result. None of it is erased by a schema rollback; there is
-- no override, no deletion and no retention decision. V49 seeded nothing and changed no earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V49 row is a separate, controlled operator step, taken only
-- when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
-- ---------------------------------------------------------------------------------------------
BEGIN;
LOCK TABLE payroll.payment_reconciliation, payroll.payment_reconciliation_inquiry IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.payment_reconciliation)
        OR EXISTS (SELECT 1 FROM payroll.payment_reconciliation_inquiry) THEN
        RAISE EXCEPTION 'V49 rollback refused: payment reconciliation or inquiry rows exist';
    END IF;
END
$$;

DROP TABLE payroll.payment_reconciliation_inquiry;
DROP TABLE payroll.payment_reconciliation;
DROP FUNCTION payroll.payment_reconciliation_inquiry_exact();
DROP FUNCTION payroll.payment_reconciliation_exact();
DROP FUNCTION payroll.payment_reconciliation_check(uuid, uuid);
DROP FUNCTION payroll.payment_reconciliation_inquiry_guard();
DROP FUNCTION payroll.payment_reconciliation_guard();
DROP FUNCTION payroll.payment_reconciliation_transition_ok(text, text);
DROP FUNCTION payroll.payment_reconciliation_immutable();
COMMIT;
