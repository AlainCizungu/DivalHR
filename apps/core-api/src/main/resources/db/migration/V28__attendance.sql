-- MVP-060A (Issue #113): an employee assigned to an operational scheduled shift checks in and out
-- online; the server's instant is authoritative. First, deliberately narrow attendance increment:
-- no supervisor recording, correction, exception, offline queue or configurable window.
--
-- Ownership: the people module owns both tables; only the bounded
-- com.divalhr.core.people.schedule package (its attendance sub-package, ADR 0016) reads or writes
-- them. The guards read the module's own schedule, coverage and employment tables (same module).
--
-- Classification: Restricted HR/Operations. The verified subject is evidence only, never returned.
--
-- Windows (named constants of AttendanceWindow, mirrored here; not tenant-configurable):
--   * check-in from 2 hours before the scheduled start (inclusive) until the scheduled end
--     (exclusive);
--   * check-out strictly after the check-in, until 24 hours after the scheduled end (inclusive).
--
-- Database authority (V28):
--   * people.attendance_event is append-only evidence (attendance_event_immutable, _no_truncate):
--     one CHECKED_IN and at most one CHECKED_OUT per record (attendance_event_record_type_unique),
--     each with the server-recorded instant, the unchanged verified subject and a digest of the
--     idempotency reference. A CHECKED_IN event is accepted only for a slot of an operational
--     schedule whose current coverage row is the employee's (the published assignee or the
--     approved substitute), inside the check-in window, with a qualifying employment (covering
--     every local date the shift occupies and placed at the schedule's site on its first date);
--     a CHECKED_OUT event only for the same record's slot, schedule, employee and employment,
--     while the record is open, strictly after its check-in and inside the check-out window
--     (attendance_event_guard). Neither depends on the shift staying operational afterwards:
--     cancellation, replacement and substitution never rewrite recorded evidence;
--   * people.attendance_record is a guarded projection: one row per slot and employee
--     (_slot_employee_unique), bound to the immutable slot, schedule and instants (_slot), to the
--     employee's employment (_employment) and to its own CHECKED_IN event (_check_in) and, once
--     completed, its CHECKED_OUT event (_check_out); check_out_until is the scheduled end plus 24
--     hours (_check_out_until); status is derived (OPEN or COMPLETED). It is
--     inserted only OPEN; the single permitted update attaches the check-out, after its event,
--     and changes nothing else (attendance_record_guard); never deleted or truncated;
--   * an employee has at most one actionable open record: the open records' ranges [check-in,
--     check_out_until] never overlap (attendance_record_one_open); the range includes
--     check_out_until because a check-out is still permitted at that instant. An open record whose
--     check-out window has closed stays OPEN as evidence for a later correction story (MVP-062)
--     and no longer blocks a new check-in (architect decision on Issue #113);
--   * deferred (attendance_exact, per record): every event has its record and agrees with it; a
--     CHECKED_OUT event exists exactly when the record is completed.
-- No backfill: both tables start empty.
--
-- Indexes: attendance_record_history (an employee's records, newest shift first);
-- attendance_record_slot_employee_unique (a slot's record for an employee).
--
-- Rollback: db/rollback/V28__rollback.sql (manual, never run by Flyway). It restores V27 exactly
-- only while no attendance event or record exists; there is no override, and it never touches
-- flyway_schema_history.

-- Whether an employment covers the dates and is placed at the site on the first date.
CREATE FUNCTION people.attendance_employment_qualifies(
    p_tenant uuid, p_employment uuid, p_employee uuid, p_site uuid, p_first date, p_last date)
    RETURNS boolean
    LANGUAGE sql STABLE
AS $fn$
SELECT EXISTS (SELECT 1 FROM people.employment e
        WHERE e.tenant_id = p_tenant AND e.id = p_employment AND e.employee_id = p_employee
            AND e.effective_from <= p_first
            AND (e.effective_to IS NULL OR e.effective_to >= p_last))
    AND EXISTS (SELECT 1 FROM people.employment_assignment a
        WHERE a.tenant_id = p_tenant AND a.employment_id = p_employment
            AND a.kind = 'PLACEMENT' AND a.superseded_by_change_id IS NULL
            AND a.site_id = p_site AND a.effective_from <= p_first
            AND (a.effective_to IS NULL OR a.effective_to >= p_first))
$fn$;

CREATE FUNCTION people.attendance_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_immutable',
        MESSAGE = 'attendance evidence is never changed or deleted';
END
$fn$;

CREATE FUNCTION people.attendance_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_no_truncate',
        MESSAGE = 'attendance data is never truncated';
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- The projection (created first: the evidence references it, deferred)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE people.attendance_record (
    tenant_id          uuid        NOT NULL,
    id                 uuid        NOT NULL,
    schedule_id        uuid        NOT NULL,
    slot_id            uuid        NOT NULL,
    employee_id        uuid        NOT NULL,
    employment_id      uuid        NOT NULL,
    slot_start_at      timestamptz NOT NULL,
    slot_end_at        timestamptz NOT NULL,
    check_out_until    timestamptz NOT NULL,
    check_in_event_id  uuid        NOT NULL,
    checked_in_at      timestamptz NOT NULL,
    check_out_event_id uuid,
    checked_out_at     timestamptz,
    status             text        NOT NULL
        GENERATED ALWAYS AS (CASE WHEN check_out_event_id IS NULL THEN 'OPEN' ELSE 'COMPLETED' END)
            STORED,
    check_in_type      text        NOT NULL GENERATED ALWAYS AS ('CHECKED_IN') STORED,
    check_out_type     text        NOT NULL GENERATED ALWAYS AS ('CHECKED_OUT') STORED,
    CONSTRAINT attendance_record_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT attendance_record_key
        UNIQUE (tenant_id, id, slot_id, schedule_id, employee_id, employment_id),
    CONSTRAINT attendance_record_slot_employee_unique UNIQUE (tenant_id, slot_id, employee_id),
    CONSTRAINT attendance_record_slot
        FOREIGN KEY (tenant_id, slot_id, schedule_id, slot_start_at, slot_end_at)
            REFERENCES people.work_schedule_slot (tenant_id, id, schedule_id, start_at, end_at),
    CONSTRAINT attendance_record_employment
        FOREIGN KEY (tenant_id, employment_id, employee_id)
            REFERENCES people.employment (tenant_id, id, employee_id),
    CONSTRAINT attendance_record_check_out_shape
        CHECK ((check_out_event_id IS NULL) = (checked_out_at IS NULL)),
    CONSTRAINT attendance_record_order
        CHECK (checked_out_at IS NULL OR checked_out_at > checked_in_at),
    CONSTRAINT attendance_record_check_in_window
        CHECK (checked_in_at >= slot_start_at - INTERVAL '2 hours' AND checked_in_at < slot_end_at),
    CONSTRAINT attendance_record_check_out_until
        CHECK (check_out_until = slot_end_at + INTERVAL '24 hours'),
    CONSTRAINT attendance_record_check_out_window
        CHECK (checked_out_at IS NULL OR checked_out_at <= check_out_until),
    CONSTRAINT attendance_record_one_open
        EXCLUDE USING gist (tenant_id WITH =, employee_id WITH =,
            tstzrange(checked_in_at, check_out_until, '[]') WITH &&)
        WHERE (check_out_event_id IS NULL)
);

COMMENT ON TABLE people.attendance_record IS
    'MVP-060A attendance on a scheduled shift (Restricted HR/Operations). Guarded projection of people.attendance_event: inserted OPEN, completed once, never deleted.';

CREATE INDEX attendance_record_history
    ON people.attendance_record (tenant_id, employee_id, slot_start_at DESC, id DESC);

-- ---------------------------------------------------------------------------------------------
-- Evidence (append-only)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE people.attendance_event (
    tenant_id          uuid        NOT NULL,
    id                 uuid        NOT NULL,
    record_id          uuid        NOT NULL,
    schedule_id        uuid        NOT NULL,
    slot_id            uuid        NOT NULL,
    employee_id        uuid        NOT NULL,
    employment_id      uuid        NOT NULL,
    event_type         text        NOT NULL,
    recorded_at        timestamptz NOT NULL,
    recorded_by        text        NOT NULL,
    idempotency_digest text        NOT NULL,
    CONSTRAINT attendance_event_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT attendance_event_record_type_unique UNIQUE (tenant_id, record_id, event_type),
    -- The target of the record's check-in and check-out references.
    CONSTRAINT attendance_event_key UNIQUE (tenant_id, id, record_id, event_type, recorded_at),
    CONSTRAINT attendance_event_record
        FOREIGN KEY (tenant_id, record_id, slot_id, schedule_id, employee_id, employment_id)
            REFERENCES people.attendance_record
                (tenant_id, id, slot_id, schedule_id, employee_id, employment_id)
            DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT attendance_event_type CHECK (event_type IN ('CHECKED_IN', 'CHECKED_OUT')),
    CONSTRAINT attendance_event_by_length CHECK (char_length(recorded_by) BETWEEN 1 AND 255),
    CONSTRAINT attendance_event_idempotency_digest CHECK (idempotency_digest ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE people.attendance_event IS
    'MVP-060A check-in and check-out evidence (Restricted HR/Operations). Append-only; the server instant and the verified subject as recorded.';

ALTER TABLE people.attendance_record
    ADD CONSTRAINT attendance_record_check_in
        FOREIGN KEY (tenant_id, check_in_event_id, id, check_in_type, checked_in_at)
            REFERENCES people.attendance_event (tenant_id, id, record_id, event_type, recorded_at);
ALTER TABLE people.attendance_record
    ADD CONSTRAINT attendance_record_check_out
        FOREIGN KEY (tenant_id, check_out_event_id, id, check_out_type, checked_out_at)
            REFERENCES people.attendance_event (tenant_id, id, record_id, event_type, recorded_at);

CREATE TRIGGER attendance_event_immutable
    BEFORE UPDATE OR DELETE ON people.attendance_event
    FOR EACH ROW EXECUTE FUNCTION people.attendance_immutable();
CREATE TRIGGER attendance_event_no_truncate
    BEFORE TRUNCATE ON people.attendance_event
    FOR EACH STATEMENT EXECUTE FUNCTION people.attendance_no_truncate();

-- A check-in: an operational schedule's slot covered by the employee now, inside the window, with
-- a qualifying employment. A check-out: the open record's own slot and people, after its check-in
-- and inside the window.
CREATE FUNCTION people.attendance_event_guard() RETURNS trigger
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

CREATE TRIGGER attendance_event_guard
    BEFORE INSERT ON people.attendance_event
    FOR EACH ROW EXECUTE FUNCTION people.attendance_event_guard();

-- The projection: inserted open; the one update attaches the check-out and nothing else.
CREATE FUNCTION people.attendance_record_guard() RETURNS trigger
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

CREATE TRIGGER attendance_record_guard
    BEFORE INSERT OR UPDATE OR DELETE ON people.attendance_record
    FOR EACH ROW EXECUTE FUNCTION people.attendance_record_guard();
CREATE TRIGGER attendance_record_no_truncate
    BEFORE TRUNCATE ON people.attendance_record
    FOR EACH STATEMENT EXECUTE FUNCTION people.attendance_no_truncate();

-- ---------------------------------------------------------------------------------------------
-- Projection and evidence agree at commit
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.attendance_exact() RETURNS trigger
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

CREATE CONSTRAINT TRIGGER attendance_event_exact
    AFTER INSERT ON people.attendance_event
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.attendance_exact();
CREATE CONSTRAINT TRIGGER attendance_record_exact
    AFTER INSERT OR UPDATE ON people.attendance_record
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.attendance_exact();
