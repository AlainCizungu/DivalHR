-- Manual rollback for V28__attendance.sql (MVP-060A, Issue #113). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V27 schema exactly, but it
-- REFUSES once any attendance event or record exists: that evidence must never be erased by a
-- schema rollback. There is no override.
--
-- It does NOT edit flyway_schema_history: removing the V28 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.attendance_event, people.attendance_record IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.attendance_event)
        OR EXISTS (SELECT 1 FROM people.attendance_record) THEN
        RAISE EXCEPTION 'V28 rollback refused: attendance evidence exists';
    END IF;
END
$$;
ALTER TABLE people.attendance_record DROP CONSTRAINT attendance_record_check_out;
ALTER TABLE people.attendance_record DROP CONSTRAINT attendance_record_check_in;
DROP TABLE people.attendance_event;
DROP TABLE people.attendance_record;
DROP FUNCTION people.attendance_exact();
DROP FUNCTION people.attendance_record_guard();
DROP FUNCTION people.attendance_event_guard();
DROP FUNCTION people.attendance_no_truncate();
DROP FUNCTION people.attendance_immutable();
DROP FUNCTION people.attendance_employment_qualifies(uuid, uuid, uuid, uuid, date, date);
COMMIT;
