-- Manual rollback for V31__attendance_correction.sql (MVP-062, Issue #126). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V30 schema exactly, but it
-- REFUSES once any attendance correction exists, or any record was created or corrected by one:
-- that evidence (the before and after instants, the reason, the explanation and the corrected
-- projection) must never be erased by a schema rollback. There is no override. Uncorrected
-- attendance survives unchanged: its instants are still its events' instants.
--
-- It does NOT edit flyway_schema_history: removing the V31 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.attendance_event, people.attendance_record, people.attendance_correction
    IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.attendance_correction)
        OR EXISTS (SELECT 1 FROM people.attendance_record
            WHERE correction_version <> 0 OR corrected_at IS NOT NULL
                OR check_in_event_id IS NULL) THEN
        RAISE EXCEPTION 'V31 rollback refused: attendance correction evidence exists';
    END IF;
END
$$;

DROP TRIGGER attendance_correction_exact ON people.attendance_correction;
DROP TRIGGER attendance_correction_guard ON people.attendance_correction;
DROP TABLE people.attendance_correction;
DROP FUNCTION people.attendance_correction_guard();
DROP FUNCTION people.attendance_correction_explanation_valid(text);

-- V28's functions, exactly.
CREATE OR REPLACE FUNCTION people.attendance_event_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_site      uuid;
    v_start_at  timestamptz;
    v_end_at    timestamptz;
    v_first     date;
    v_last      date;
    v_record    people.attendance_record%ROWTYPE;
BEGIN
    IF NEW.event_type = 'CHECKED_OUT' THEN
        SELECT * INTO v_record FROM people.attendance_record r
            WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.record_id
            FOR UPDATE;
        IF NOT FOUND
            OR v_record.slot_id <> NEW.slot_id OR v_record.schedule_id <> NEW.schedule_id
            OR v_record.employee_id <> NEW.employee_id
            OR v_record.employment_id <> NEW.employment_id
            OR v_record.check_out_event_id IS NOT NULL
            OR NEW.recorded_at <= v_record.checked_in_at
            OR NEW.recorded_at > v_record.check_out_until THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_event_check_out',
                MESSAGE = 'a check-out completes its own open record inside the check-out window';
        END IF;
        RETURN NEW;
    END IF;
    -- Serializes with terminal actions and substitution decisions of the schedule.
    SELECT s.site_id INTO v_site FROM people.work_schedule s
        WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.schedule_id
        FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_event_check_in',
            MESSAGE = 'a check-in names a slot of an operational schedule covered by its employee';
    END IF;
    SELECT l.start_at, l.end_at, l.start_local::date,
            people.work_schedule_slot_last_date(l.start_local, l.end_local)
        INTO v_start_at, v_end_at, v_first, v_last
        FROM people.work_schedule_slot l
        WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.slot_id
            AND l.schedule_id = NEW.schedule_id;
    IF NOT FOUND
        OR people.work_schedule_terminal(NEW.tenant_id, NEW.schedule_id)
        OR NOT EXISTS (SELECT 1 FROM people.work_schedule_slot_coverage c
            WHERE c.tenant_id = NEW.tenant_id AND c.slot_id = NEW.slot_id
                AND c.employee_id = NEW.employee_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_event_check_in',
            MESSAGE = 'a check-in names a slot of an operational schedule covered by its employee';
    END IF;
    IF NEW.recorded_at < v_start_at - INTERVAL '2 hours' OR NEW.recorded_at >= v_end_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_event_check_in_window',
            MESSAGE = 'a check-in is recorded inside the check-in window';
    END IF;
    IF NOT people.attendance_employment_qualifies(NEW.tenant_id, NEW.employment_id,
            NEW.employee_id, v_site, v_first, v_last) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'attendance_event_employment_qualifies',
            MESSAGE = 'a check-in names the employee''s qualifying employment';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE OR REPLACE FUNCTION people.attendance_record_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_record_immutable',
            MESSAGE = 'an attendance record is never deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.check_out_event_id IS NOT NULL OR NEW.checked_out_at IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_record_guard',
                MESSAGE = 'an attendance record is inserted open';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.check_out_event_id IS NOT NULL
        OR NEW.check_out_event_id IS NULL
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.id IS DISTINCT FROM OLD.id
        OR NEW.schedule_id IS DISTINCT FROM OLD.schedule_id
        OR NEW.slot_id IS DISTINCT FROM OLD.slot_id
        OR NEW.employee_id IS DISTINCT FROM OLD.employee_id
        OR NEW.employment_id IS DISTINCT FROM OLD.employment_id
        OR NEW.slot_start_at IS DISTINCT FROM OLD.slot_start_at
        OR NEW.slot_end_at IS DISTINCT FROM OLD.slot_end_at
        OR NEW.check_out_until IS DISTINCT FROM OLD.check_out_until
        OR NEW.check_in_event_id IS DISTINCT FROM OLD.check_in_event_id
        OR NEW.checked_in_at IS DISTINCT FROM OLD.checked_in_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_record_guard',
            MESSAGE = 'an open attendance record only receives its single check-out';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE OR REPLACE FUNCTION people.attendance_exact() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_record people.attendance_record%ROWTYPE;
    v_record_id uuid;
BEGIN
    IF TG_TABLE_NAME = 'attendance_event' THEN
        v_record_id := NEW.record_id;
    ELSE
        v_record_id := NEW.id;
    END IF;
    SELECT * INTO v_record FROM people.attendance_record r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = v_record_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_exact',
            MESSAGE = 'every attendance event has its record';
    END IF;
    IF (SELECT count(*) FROM people.attendance_event e
            WHERE e.tenant_id = v_record.tenant_id AND e.record_id = v_record.id
                AND e.event_type = 'CHECKED_IN' AND e.id = v_record.check_in_event_id
                AND e.recorded_at = v_record.checked_in_at) <> 1
        OR (SELECT count(*) FROM people.attendance_event e
            WHERE e.tenant_id = v_record.tenant_id AND e.record_id = v_record.id
                AND e.event_type = 'CHECKED_OUT')
            <> (CASE WHEN v_record.check_out_event_id IS NULL THEN 0 ELSE 1 END) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_exact',
            MESSAGE = 'an attendance record and its events agree';
    END IF;
    RETURN NULL;
END
$fn$;

-- The projection's V28 shape (every remaining record is uncorrected: its instants are its
-- events', so the instant-bound references hold).
ALTER TABLE people.attendance_record DROP CONSTRAINT attendance_record_one_open;
ALTER TABLE people.attendance_record DROP CONSTRAINT attendance_record_check_out_shape;
ALTER TABLE people.attendance_record DROP CONSTRAINT attendance_record_origin;
ALTER TABLE people.attendance_record DROP CONSTRAINT attendance_record_correction_shape;
ALTER TABLE people.attendance_record DROP CONSTRAINT attendance_record_correction_version;
ALTER TABLE people.attendance_record DROP CONSTRAINT attendance_record_check_out;
ALTER TABLE people.attendance_record DROP CONSTRAINT attendance_record_check_in;
ALTER TABLE people.attendance_record
    ALTER COLUMN status SET EXPRESSION AS
        (CASE WHEN check_out_event_id IS NULL THEN 'OPEN' ELSE 'COMPLETED' END);
ALTER TABLE people.attendance_record ALTER COLUMN check_in_event_id SET NOT NULL;
ALTER TABLE people.attendance_record DROP COLUMN corrected_at;
ALTER TABLE people.attendance_record DROP COLUMN correction_version;
ALTER TABLE people.attendance_record
    ADD CONSTRAINT attendance_record_check_out_shape
        CHECK ((check_out_event_id IS NULL) = (checked_out_at IS NULL)),
    ADD CONSTRAINT attendance_record_one_open
        EXCLUDE USING gist (tenant_id WITH =, employee_id WITH =,
            tstzrange(checked_in_at, check_out_until, '[]') WITH &&)
        WHERE (check_out_event_id IS NULL),
    ADD CONSTRAINT attendance_record_check_in
        FOREIGN KEY (tenant_id, check_in_event_id, id, check_in_type, checked_in_at)
            REFERENCES people.attendance_event (tenant_id, id, record_id, event_type, recorded_at),
    ADD CONSTRAINT attendance_record_check_out
        FOREIGN KEY (tenant_id, check_out_event_id, id, check_out_type, checked_out_at)
            REFERENCES people.attendance_event (tenant_id, id, record_id, event_type, recorded_at);
ALTER TABLE people.attendance_event DROP CONSTRAINT attendance_event_record_key;

COMMENT ON TABLE people.attendance_record IS
    'MVP-060A attendance on a scheduled shift (Restricted HR/Operations). Guarded projection of people.attendance_event: inserted OPEN, completed once, never deleted.';
COMMIT;
