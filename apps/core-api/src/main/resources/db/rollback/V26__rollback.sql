-- Manual rollback for V26__work_schedule_lifecycle.sql (MVP-050B, Issue #101). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V25 schema exactly, but it
-- REFUSES once any cancellation or replacement exists (that evidence must never be erased by a
-- schema rollback) or when the coverage projections no longer match every schedule and assigned
-- slot exactly (the V25 exclusions could then not be restored faithfully). There is no override.
--
-- It does NOT edit flyway_schema_history: removing the V26 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.work_schedule, people.work_schedule_slot, people.work_schedule_cancellation,
    people.work_schedule_replacement, people.work_schedule_site_coverage,
    people.work_schedule_slot_coverage IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.work_schedule_cancellation)
        OR EXISTS (SELECT 1 FROM people.work_schedule_replacement) THEN
        RAISE EXCEPTION 'V26 rollback refused: cancelled or replaced work schedules exist';
    END IF;
    IF (SELECT count(*) FROM people.work_schedule_site_coverage)
            <> (SELECT count(*) FROM people.work_schedule)
        OR (SELECT count(*) FROM people.work_schedule_slot_coverage)
            <> (SELECT count(*) FROM people.work_schedule_slot WHERE assigned_employee_id IS NOT NULL)
        OR EXISTS (SELECT 1 FROM people.work_schedule s WHERE NOT EXISTS (
            SELECT 1 FROM people.work_schedule_site_coverage c
            WHERE c.tenant_id = s.tenant_id AND c.schedule_id = s.id))
        OR EXISTS (SELECT 1 FROM people.work_schedule_slot l
            WHERE l.assigned_employee_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM people.work_schedule_slot_coverage c
                WHERE c.tenant_id = l.tenant_id AND c.slot_id = l.id)) THEN
        RAISE EXCEPTION 'V26 rollback refused: the coverage projections do not match every schedule';
    END IF;
END
$$;
DROP TABLE people.work_schedule_slot_coverage;
DROP TABLE people.work_schedule_site_coverage;
DROP TABLE people.work_schedule_replacement;
DROP TABLE people.work_schedule_cancellation;
DROP TRIGGER work_schedule_covered ON people.work_schedule;
DROP FUNCTION people.work_schedule_coverage_exact();
DROP FUNCTION people.work_schedule_coverage_no_truncate();
DROP FUNCTION people.work_schedule_coverage_guard();
DROP FUNCTION people.work_schedule_terminal_guard();
DROP FUNCTION people.work_schedule_terminal(uuid, uuid);
DROP FUNCTION people.work_schedule_terminal_no_truncate();
DROP FUNCTION people.work_schedule_terminal_immutable();
ALTER TABLE people.work_schedule_slot DROP CONSTRAINT work_schedule_slot_coverage_key;
ALTER TABLE people.work_schedule DROP CONSTRAINT work_schedule_coverage_key;
ALTER TABLE people.work_schedule
    ADD CONSTRAINT work_schedule_site_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, site_id WITH =, daterange(first_date, last_date, '[]') WITH &&);
ALTER TABLE people.work_schedule_slot
    ADD CONSTRAINT work_schedule_slot_employee_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, assigned_employee_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&)
        WHERE (assigned_employee_id IS NOT NULL);
COMMIT;
