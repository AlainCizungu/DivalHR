-- Manual rollback for V38__payroll_input_versions_locks_exports.sql (MVP-092, Issue #149). NEVER run
-- by Flyway.
--
-- Prefer rolling back only the application. This script restores the V37 schema exactly, but it
-- REFUSES once any replacement version, final lock or export exists: those are the traceable record
-- of what worked-time evidence was finalized and handed on, and they are never erased by a schema
-- rollback. There is no override and no deletion. Nothing outside the V38 tables, triggers and
-- functions is touched; V38 changed no earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V38 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE payroll.input_export_row, payroll.input_export, payroll.input_run_lock,
    payroll.input_run_supersession, payroll.input_run IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.input_run_supersession)
        OR EXISTS (SELECT 1 FROM payroll.input_run_lock)
        OR EXISTS (SELECT 1 FROM payroll.input_export)
        OR EXISTS (SELECT 1 FROM payroll.input_export_row)
        OR EXISTS (SELECT 1 FROM payroll.input_run WHERE version > 1) THEN
        RAISE EXCEPTION 'V38 rollback refused: payroll-input version, lock or export evidence exists';
    END IF;
END
$$;

DROP TRIGGER payroll_input_export_exact ON payroll.input_export;
DROP TRIGGER payroll_input_run_chain ON payroll.input_run;
DROP TABLE payroll.input_export_row;
DROP TABLE payroll.input_export;
DROP TABLE payroll.input_run_lock;
DROP TABLE payroll.input_run_supersession;
DROP FUNCTION payroll.input_export_exact();
DROP FUNCTION payroll.input_run_chain();
DROP FUNCTION payroll.input_lock_valid();
DROP FUNCTION payroll.input_supersession_valid();
DROP FUNCTION payroll.input_scope_lock(uuid, uuid, date, date);
COMMIT;
