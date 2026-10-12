-- Manual rollback for V29__supervisor_attendance.sql (MVP-060B, Issue #115). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V28 schema exactly, but it
-- REFUSES once any supervisor action or SUPERVISOR attendance event exists: that evidence must
-- never be erased by a schema rollback. There is no override. Existing SELF attendance (events and
-- records) survives unchanged: only the recording_method column, which is SELF for all of it, is
-- removed.
--
-- It does NOT edit flyway_schema_history: removing the V29 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.attendance_event, people.attendance_record, people.attendance_supervisor_action
    IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.attendance_supervisor_action)
        OR EXISTS (SELECT 1 FROM people.attendance_event WHERE recording_method <> 'SELF') THEN
        RAISE EXCEPTION 'V29 rollback refused: supervisor attendance evidence exists';
    END IF;
END
$$;
DROP TRIGGER attendance_event_supervisor_exact ON people.attendance_event;
DROP TABLE people.attendance_supervisor_action;
DROP FUNCTION people.attendance_supervisor_exact();
DROP FUNCTION people.attendance_supervisor_action_guard();
ALTER TABLE people.attendance_event DROP CONSTRAINT attendance_event_supervisor_key;
ALTER TABLE people.attendance_event DROP CONSTRAINT attendance_event_recording_method;
ALTER TABLE people.attendance_event DROP COLUMN recording_method;
COMMIT;
