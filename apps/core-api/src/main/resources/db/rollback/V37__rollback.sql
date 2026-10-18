-- Manual rollback for V37__payroll_inputs.sql (MVP-090A, Issue #141). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V36 schema exactly, but it
-- REFUSES once any payroll-input run exists: a run is the traceable record of what approved time
-- was handed to payroll preparation, and it is never erased by a schema rollback. There is no
-- override and no deletion. Nothing outside the V37 tables, their triggers and their functions is
-- touched; V37 changed no earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V37 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE payroll.input_run, payroll.input_source_week, payroll.input_employee,
    payroll.input_source_row IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.input_run) THEN
        RAISE EXCEPTION 'V37 rollback refused: payroll-input evidence exists';
    END IF;
END
$$;

DROP TABLE payroll.input_source_row;
DROP TABLE payroll.input_employee;
DROP TABLE payroll.input_source_week;
DROP TABLE payroll.input_run;
DROP FUNCTION payroll.input_run_exact();
DROP FUNCTION payroll.input_no_truncate();
DROP FUNCTION payroll.input_immutable();
COMMIT;
