-- ---------------------------------------------------------------------------------------------
-- Manual rollback for V47__payroll_payment_destinations_batches.sql (MVP-095A, Issue #183). NEVER run by
-- Flyway.
--
-- Prefer rolling back only the application. This script restores the V46 schema exactly, but it REFUSES
-- once any MVP-095A evidence exists: payment destinations, destination changes, payment batches, their
-- lines and their cancellation evidence are the record of how salaries were prepared for payment. None of
-- it is erased by a schema rollback; there is no override, no deletion and no retention decision. V47
-- seeded nothing. Besides its own tables, triggers and functions, the script removes the void-guard
-- trigger V47 added to payroll.payroll_package; V47 changed no other earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V47 row is a separate, controlled operator step,
-- taken only when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
-- ---------------------------------------------------------------------------------------------
BEGIN;
LOCK TABLE payroll.payment_destination, payroll.payment_batch, payroll.payment_batch_line,
    payroll.payment_batch_cancel IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.payment_destination)
        OR EXISTS (SELECT 1 FROM payroll.payment_batch)
        OR EXISTS (SELECT 1 FROM payroll.payment_batch_line)
        OR EXISTS (SELECT 1 FROM payroll.payment_batch_cancel) THEN
        RAISE EXCEPTION 'V47 rollback refused: payment destination, batch, line or cancellation rows exist';
    END IF;
END
$$;

DROP TRIGGER payroll_package_payment_guard ON payroll.payroll_package;
DROP TABLE payroll.payment_batch_cancel;
DROP TABLE payroll.payment_batch_line;
DROP TABLE payroll.payment_batch;
DROP TABLE payroll.payment_destination;
DROP FUNCTION payroll.payment_batch_blocks_void();
DROP FUNCTION payroll.payment_batch_line_exact();
DROP FUNCTION payroll.payment_batch_exact();
DROP FUNCTION payroll.payment_batch_check(uuid, uuid);
DROP FUNCTION payroll.payment_batch_line_guard();
DROP FUNCTION payroll.payment_batch_cancel_guard();
DROP FUNCTION payroll.payment_batch_guard();
DROP FUNCTION payroll.payment_destination_guard();
DROP FUNCTION payroll.payment_immutable();
COMMIT;
