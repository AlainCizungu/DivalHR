-- Manual rollback for V30__offline_attendance_sync.sql (MVP-061A, Issue #122). NEVER run by
-- Flyway.
--
-- Prefer rolling back only the application. This script restores the V29 schema exactly, but it
-- REFUSES once any EMPLOYEE_OFFLINE_SYNC attendance event exists: that evidence (and its claimed
-- device time, offset and receipt instant) must never be erased by a schema rollback. There is no
-- override. Existing SELF and SUPERVISOR attendance survives unchanged: the three removed columns
-- are NULL for all of it.
--
-- It does NOT edit flyway_schema_history: removing the V30 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.attendance_event, people.attendance_record IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.attendance_event
            WHERE recording_method = 'EMPLOYEE_OFFLINE_SYNC' OR client_event_id IS NOT NULL
                OR device_offset_minutes IS NOT NULL OR received_at IS NOT NULL) THEN
        RAISE EXCEPTION 'V30 rollback refused: offline attendance evidence exists';
    END IF;
END
$$;
DROP TRIGGER attendance_event_offline_guard ON people.attendance_event;
DROP FUNCTION people.attendance_event_offline_guard();
ALTER TABLE people.attendance_event DROP CONSTRAINT attendance_event_client_event_unique;
ALTER TABLE people.attendance_event DROP CONSTRAINT attendance_event_offline_receipt;
ALTER TABLE people.attendance_event DROP CONSTRAINT attendance_event_offline_offset;
ALTER TABLE people.attendance_event DROP CONSTRAINT attendance_event_offline_shape;
ALTER TABLE people.attendance_event DROP CONSTRAINT attendance_event_recording_method;
ALTER TABLE people.attendance_event
    ADD CONSTRAINT attendance_event_recording_method
        CHECK (recording_method IN ('SELF', 'SUPERVISOR'));
ALTER TABLE people.attendance_event DROP COLUMN received_at;
ALTER TABLE people.attendance_event DROP COLUMN device_offset_minutes;
ALTER TABLE people.attendance_event DROP COLUMN client_event_id;
COMMIT;
