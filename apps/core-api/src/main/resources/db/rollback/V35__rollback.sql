-- Manual rollback for V35__timesheet_export.sql (MVP-070E, Issue #134). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V34 schema exactly, but it
-- REFUSES once any timesheet export or export row exists: an export is the record of what was
-- handed to payroll or accounting and must never be erased by a schema rollback. There is no
-- override. Nothing outside the two V35 tables, their triggers and their functions is touched.
--
-- It does NOT edit flyway_schema_history: removing the V35 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.timesheet_export, people.timesheet_export_row IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.timesheet_export)
        OR EXISTS (SELECT 1 FROM people.timesheet_export_row) THEN
        RAISE EXCEPTION 'V35 rollback refused: timesheet export evidence exists';
    END IF;
END
$$;

DROP TABLE people.timesheet_export_row;
DROP TABLE people.timesheet_export;
DROP FUNCTION people.timesheet_export_exact();
DROP FUNCTION people.timesheet_export_row_bind();
DROP FUNCTION people.timesheet_export_bind();
COMMIT;
