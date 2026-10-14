-- Manual rollback for V32__timesheet.sql (MVP-070A, Issue #128). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V31 schema exactly, but it
-- REFUSES once any timesheet generation, timesheet, revision or row exists: generated drafts are
-- evidence of the worked time shown to employees and must never be erased by a schema rollback.
-- There is no override. Nothing outside the four V32 tables and their functions is touched.
--
-- It does NOT edit flyway_schema_history: removing the V32 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.timesheet_generation, people.timesheet, people.timesheet_revision,
    people.timesheet_row IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.timesheet_generation)
        OR EXISTS (SELECT 1 FROM people.timesheet)
        OR EXISTS (SELECT 1 FROM people.timesheet_revision)
        OR EXISTS (SELECT 1 FROM people.timesheet_row) THEN
        RAISE EXCEPTION 'V32 rollback refused: timesheet evidence exists';
    END IF;
END
$$;

DROP TABLE people.timesheet_row;
ALTER TABLE people.timesheet DROP CONSTRAINT timesheet_current;
DROP TABLE people.timesheet_revision;
DROP TABLE people.timesheet;
DROP TABLE people.timesheet_generation;
DROP FUNCTION people.timesheet_revision_exact();
DROP FUNCTION people.timesheet_revision_guard();
DROP FUNCTION people.timesheet_guard();
DROP FUNCTION people.timesheet_generation_guard();
DROP FUNCTION people.timesheet_no_truncate();
DROP FUNCTION people.timesheet_immutable();
COMMIT;
