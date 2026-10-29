-- Manual rollback for V43__payroll_monthly_compensation.sql (MVP-093A, Issue #175). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V42 schema exactly, but it
-- REFUSES once any MVP-093A data exists: compensation facts and changes are the record of what was
-- agreed for an employee and are never erased by a schema rollback. There is no override, no
-- deletion and no retention decision. Nothing outside the V43 tables, triggers and functions is
-- touched; V43 changed no earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V43 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE payroll.compensation, payroll.compensation_change IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.compensation) OR EXISTS (SELECT 1 FROM payroll.compensation_change) THEN
        RAISE EXCEPTION 'V43 rollback refused: compensation or compensation change rows exist';
    END IF;
END
$$;

DROP TABLE payroll.compensation;
DROP TABLE payroll.compensation_change;
DROP FUNCTION payroll.compensation_change_exact();
DROP FUNCTION payroll.compensation_exact();
DROP FUNCTION payroll.compensation_guard();
DROP FUNCTION payroll.compensation_change_guard();
DROP FUNCTION payroll.compensation_text_valid(text, integer, integer);
DROP FUNCTION payroll.compensation_immutable();
COMMIT;
