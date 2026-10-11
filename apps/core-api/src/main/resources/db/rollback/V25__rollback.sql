-- Manual rollback for V25__work_schedule.sql (MVP-050A, Issue #99). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V24 schema exactly, but it
-- REFUSES once any published work schedule or slot exists: that evidence must never be erased by a
-- schema rollback, so there is no override flag. V25 changed no existing table, function or
-- constraint, so dropping its own objects restores V24.
--
-- It does NOT edit flyway_schema_history: removing the V25 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.work_schedule, people.work_schedule_slot IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.work_schedule)
        OR EXISTS (SELECT 1 FROM people.work_schedule_slot) THEN
        RAISE EXCEPTION 'V25 rollback refused: published work schedules exist';
    END IF;
END
$$;
DROP TABLE people.work_schedule_slot;
DROP TABLE people.work_schedule;
DROP FUNCTION people.work_schedule_complete();
DROP FUNCTION people.work_schedule_slot_consistent();
DROP FUNCTION people.work_schedule_local_unique(timestamp, text, timestamptz);
DROP FUNCTION people.work_schedule_slot_no_truncate();
DROP FUNCTION people.work_schedule_slot_immutable();
DROP FUNCTION people.work_schedule_site_timezone();
DROP FUNCTION people.work_schedule_no_truncate();
DROP FUNCTION people.work_schedule_immutable();
DROP FUNCTION people.work_schedule_label_valid(text);
COMMIT;
