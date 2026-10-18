-- Manual rollback for V36__timesheet_period_lock.sql (MVP-071, Issue #139). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V35 schema exactly, but it
-- REFUSES once any period lock or unlock action, or any lock projection, exists: a lock is the
-- record that a week was handed over as stable payroll-source evidence, and an unlock the record
-- of who reopened it and why; neither is ever erased by a schema rollback. There is no override.
-- Nothing outside the V36 tables, their triggers and their functions is touched; V36 changed no
-- earlier function.
--
-- It does NOT edit flyway_schema_history: removing the V36 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.timesheet_period_lock, people.timesheet_period_action IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.timesheet_period_action)
        OR EXISTS (SELECT 1 FROM people.timesheet_period_lock) THEN
        RAISE EXCEPTION 'V36 rollback refused: timesheet period lock evidence exists';
    END IF;
END
$$;

DROP TRIGGER timesheet_period_guard_correction ON people.attendance_correction;
DROP TRIGGER timesheet_period_guard_generation ON people.timesheet_generation;
DROP TRIGGER timesheet_period_guard_action ON people.timesheet_action;
DROP TRIGGER timesheet_period_guard_timesheet ON people.timesheet;
DROP FUNCTION people.timesheet_period_guard_correction();
DROP FUNCTION people.timesheet_period_guard_generation();
DROP FUNCTION people.timesheet_period_guard_action();
DROP FUNCTION people.timesheet_period_guard_timesheet();
DROP FUNCTION people.timesheet_period_is_locked(uuid, uuid);
DROP TABLE people.timesheet_period_lock;
DROP TABLE people.timesheet_period_action;
DROP FUNCTION people.timesheet_period_lock_current();
DROP FUNCTION people.timesheet_period_lock_guard();
DROP FUNCTION people.timesheet_period_action_transition();
DROP FUNCTION people.timesheet_period_fully_approved(uuid, uuid, integer, integer, integer, integer);
COMMIT;
