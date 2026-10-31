-- ---------------------------------------------------------------------------------------------
-- Manual rollback for V46__payroll_approval_payslips.sql (MVP-094C, Issue #181). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V45 schema exactly, but it REFUSES
-- once any MVP-094C evidence exists: payroll packages, their source bindings, their void evidence and the
-- payslip snapshots are the record of what was approved and published to employees. None of it is erased
-- by a schema rollback; there is no override, no deletion and no retention decision. V46 seeded nothing,
-- so nothing is allowed to go with the tables. Besides its own tables, triggers and functions, the script
-- removes the two source-lock triggers V46 added to payroll.pay_run and payroll.statutory_calculation;
-- V46 changed no other earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V46 row is a separate, controlled operator step,
-- taken only when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE payroll.payroll_package, payroll.payroll_package_source, payroll.payroll_package_void,
    payroll.payroll_payslip IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.payroll_package)
        OR EXISTS (SELECT 1 FROM payroll.payroll_package_source)
        OR EXISTS (SELECT 1 FROM payroll.payroll_package_void)
        OR EXISTS (SELECT 1 FROM payroll.payroll_payslip) THEN
        RAISE EXCEPTION 'V46 rollback refused: payroll package, source, void or payslip rows exist';
    END IF;
END
$$;

DROP TRIGGER payroll_pay_run_package_lock ON payroll.pay_run;
DROP TRIGGER payroll_statutory_calculation_package_lock ON payroll.statutory_calculation;
DROP TABLE payroll.payroll_payslip;
DROP TABLE payroll.payroll_package_source;
DROP TABLE payroll.payroll_package_void;
DROP TABLE payroll.payroll_package;
DROP FUNCTION payroll.payroll_package_source_lock();
DROP FUNCTION payroll.payroll_package_child_exact();
DROP FUNCTION payroll.payroll_package_exact();
DROP FUNCTION payroll.payroll_package_approval_digest(uuid, uuid);
DROP FUNCTION payroll.payroll_payslip_guard();
DROP FUNCTION payroll.payroll_package_source_guard();
DROP FUNCTION payroll.payroll_package_void_guard();
DROP FUNCTION payroll.payroll_package_guard();
DROP FUNCTION payroll.payroll_snapshot_json(text);
DROP FUNCTION payroll.payroll_snapshot_digest(text);
DROP FUNCTION payroll.payroll_payslip_immutable();
DROP FUNCTION payroll.payroll_package_immutable();
COMMIT;
