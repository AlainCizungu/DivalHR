-- Manual rollback for V44__payroll_monthly_runs.sql (MVP-094A, Issue #177). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V43 schema exactly, but it
-- REFUSES once any MVP-094A data exists: payroll policies and runs are the record of how a contractual
-- gross was derived and are never erased by a schema rollback. There is no override, no deletion and
-- no retention decision. Nothing outside the V44 tables, triggers and functions is touched; V44 changed
-- no earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V44 row is a separate, controlled operator step,
-- taken only when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE payroll.run_policy, payroll.pay_run, payroll.pay_run_line, payroll.pay_run_line_range,
    payroll.pay_run_line_source IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.run_policy) OR EXISTS (SELECT 1 FROM payroll.pay_run)
        OR EXISTS (SELECT 1 FROM payroll.pay_run_line) OR EXISTS (SELECT 1 FROM payroll.pay_run_line_range)
        OR EXISTS (SELECT 1 FROM payroll.pay_run_line_source) THEN
        RAISE EXCEPTION 'V44 rollback refused: payroll policy or run rows exist';
    END IF;
END
$$;

DROP TABLE payroll.pay_run_line_source;
DROP TABLE payroll.pay_run_line_range;
DROP TABLE payroll.pay_run_line;
DROP TABLE payroll.pay_run;
DROP TABLE payroll.run_policy;
DROP FUNCTION payroll.pay_run_exact();
DROP FUNCTION payroll.pay_run_line_exact();
DROP FUNCTION payroll.pay_run_line_guard();
DROP FUNCTION payroll.pay_run_guard();
DROP FUNCTION payroll.run_policy_guard();
DROP FUNCTION payroll.run_immutable();
COMMIT;
