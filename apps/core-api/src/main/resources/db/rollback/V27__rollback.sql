-- Manual rollback for V27__work_schedule_substitution.sql (MVP-051, Issue #105). NEVER run by
-- Flyway.
--
-- Prefer rolling back only the application. This script restores the V26 schema exactly, but it
-- REFUSES once any substitution request, decision or pending row exists (that evidence must never
-- be erased by a schema rollback) or when the slot coverage is not again exactly the original
-- published assignments (a substitute's coverage could not survive V26's foreign key). There is
-- no override.
--
-- It does NOT edit flyway_schema_history: removing the V27 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.work_schedule, people.work_schedule_slot, people.work_schedule_cancellation,
    people.work_schedule_replacement, people.work_schedule_slot_coverage,
    people.work_schedule_substitution_request, people.work_schedule_substitution_decision,
    people.work_schedule_substitution_pending IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.work_schedule_substitution_request)
        OR EXISTS (SELECT 1 FROM people.work_schedule_substitution_decision)
        OR EXISTS (SELECT 1 FROM people.work_schedule_substitution_pending) THEN
        RAISE EXCEPTION 'V27 rollback refused: work schedule substitution evidence exists';
    END IF;
    IF EXISTS (SELECT 1 FROM people.work_schedule_slot_coverage c
            WHERE c.decision_id IS NOT NULL OR NOT EXISTS (
                SELECT 1 FROM people.work_schedule_slot l
                WHERE l.tenant_id = c.tenant_id AND l.id = c.slot_id
                    AND l.schedule_id = c.schedule_id AND l.assigned_employee_id = c.employee_id
                    AND l.start_at = c.start_at AND l.end_at = c.end_at)) THEN
        RAISE EXCEPTION 'V27 rollback refused: the slot coverage is not the original assignments';
    END IF;
END
$$;
DROP TRIGGER work_schedule_replacement_unpending ON people.work_schedule_replacement;
DROP TRIGGER work_schedule_cancellation_unpending ON people.work_schedule_cancellation;
DROP TRIGGER work_schedule_slot_coverage_bound ON people.work_schedule_slot_coverage;
DROP TRIGGER work_schedule_slot_coverage_guard ON people.work_schedule_slot_coverage;
CREATE TRIGGER work_schedule_slot_coverage_guard
    BEFORE INSERT OR UPDATE OR DELETE ON people.work_schedule_slot_coverage
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_coverage_guard();
DROP FUNCTION people.work_schedule_slot_coverage_guard();
DROP INDEX people.work_schedule_slot_coverage_employee;
ALTER TABLE people.work_schedule_slot_coverage DROP CONSTRAINT work_schedule_slot_coverage_employee;
ALTER TABLE people.work_schedule_slot_coverage
    DROP CONSTRAINT work_schedule_slot_coverage_substitution;
ALTER TABLE people.work_schedule_slot_coverage DROP CONSTRAINT work_schedule_slot_coverage_published;
ALTER TABLE people.work_schedule_slot_coverage DROP CONSTRAINT work_schedule_slot_coverage_slot;
ALTER TABLE people.work_schedule_slot_coverage DROP COLUMN published_employee_id;
ALTER TABLE people.work_schedule_slot_coverage DROP COLUMN decision_id;
ALTER TABLE people.work_schedule_slot_coverage
    ADD CONSTRAINT work_schedule_slot_coverage_slot
        FOREIGN KEY (tenant_id, slot_id, schedule_id, employee_id, start_at, end_at)
            REFERENCES people.work_schedule_slot
                (tenant_id, id, schedule_id, assigned_employee_id, start_at, end_at);
DROP TABLE people.work_schedule_substitution_pending;
DROP TABLE people.work_schedule_substitution_decision;
DROP TABLE people.work_schedule_substitution_request;
DROP FUNCTION people.work_schedule_substitution_exact();
DROP FUNCTION people.work_schedule_substitution_pending_guard();
DROP FUNCTION people.work_schedule_substitution_decision_guard();
DROP FUNCTION people.work_schedule_substitution_request_guard();
DROP FUNCTION people.work_schedule_slot_substituted(uuid, uuid);
DROP FUNCTION people.work_schedule_substitution_no_truncate();
DROP FUNCTION people.work_schedule_substitution_immutable();
DROP FUNCTION people.work_schedule_substitution_employment_qualifies(uuid, uuid, uuid, uuid, date,
    date, uuid);
DROP FUNCTION people.work_schedule_slot_last_date(timestamp, timestamp);
ALTER TABLE people.work_schedule_slot DROP CONSTRAINT work_schedule_slot_instants_key;
ALTER TABLE people.work_schedule_slot DROP CONSTRAINT work_schedule_slot_original_key;
COMMIT;
