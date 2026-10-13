-- MVP-062 (Issue #126): a tenant administrator with exact MFA corrects factual attendance
-- evidence after reviewing an exception. Every correction is explicit, reasoned, previewed,
-- concurrency-safe and append-only; the original attendance events are never changed or deleted.
--
-- Ownership: the people module; only the bounded com.divalhr.core.people.schedule package reads
-- or writes these objects (ADR 0016, amended for MVP-062).
--
-- Classification: Restricted HR/Operations. The explanation may hold personal information: it is
-- shown to tenant administrators only, never to the employee, and never enters audit metadata,
-- events, logs or errors. The verified subject is evidence only.
--
-- Shape (C62-6):
--   * people.attendance_correction is append-only (_immutable, _no_truncate): one row per committed
--     correction, numbered 1, 2, ... per record (_version_unique, contiguous: the guard requires
--     the record's current version plus one). Exactly two operations (_operation, _shape):
--       - CREATE_MISSING_RECORD (version 1, no before values): the record is created by the
--         correction itself, for a slot of a schedule that is not cancelled or replaced, for its
--         effective assignee (the substitute approved before the scheduled end, otherwise the
--         published assignee) with a qualifying employment, while no record of that slot and
--         employee exists;
--       - REPLACE_TIMES (before check-in present): the record's current effective check-in and
--         optional check-out are replaced; the before values must be exactly the record's current
--         values (attendance_correction_guard). Never the employee, employment, schedule or slot.
--     Each row binds the record, slot, schedule, employee and employment by composite keys
--     (_record deferred, _slot, _employment) and keeps the before and after instants, the closed
--     reason code, the explanation (10 to 500 characters, trimmed, no control character, markup
--     or link), the verified subject, the preview digest, a digest of the idempotency reference
--     and the server instant. The after values obey the MVP-060A windows (_check_in_window,
--     _check_out_window), strict order (_order) and the 5-minute future bound (_not_future), and
--     must change something (_changes).
--   * people.attendance_record stays the effective projection. It gains correction_version (0 for
--     uncorrected evidence) and corrected_at; check_in_event_id becomes optional (absent only for
--     a record created by a correction). Its event references bind the event, record and type
--     (attendance_event_record_key) but no longer the instant: after a correction the effective
--     instants are the latest correction's after values. status (OPEN or COMPLETED) and the
--     one-open exclusion now follow the effective check-out. The guard (attendance_record_guard)
--     permits exactly: an event-backed open insert; a correction-created insert matching its
--     CREATE_MISSING_RECORD row; the single check-out attach (a record without an effective or
--     recorded check-out); and a correction update matching its REPLACE_TIMES row (version + 1,
--     before = old values, after = new values). Never deleted or truncated.
--   * deferred (attendance_exact, per record): every event and correction has its record; the
--     record's event references and its events agree; its corrections are numbered exactly 1 to
--     correction_version; uncorrected, its instants are its events'; corrected, its check-in and
--     corrected_at are the latest correction's, and its check-out is that correction's, or (when
--     that correction left none) the check-out event attached after it.
--   * a check-out event is refused once the record has an effective check-out
--     (attendance_event_guard); the other event rules are unchanged.
-- No backfill: every existing record is uncorrected evidence (version 0) and its constraints hold
-- unchanged; the new table starts empty.
--
-- Indexes: attendance_correction_version_unique (a record's history newest first, the guard's
-- version lookup); the existing record keys serve every target lookup.
--
-- Rollback: db/rollback/V31__rollback.sql (manual, never run by Flyway). It restores V30 exactly
-- only while no correction and no correction-created or corrected record exists; there is no
-- override, and it never touches flyway_schema_history.

-- ---------------------------------------------------------------------------------------------
-- The projection: effective values and correction provenance
-- ---------------------------------------------------------------------------------------------

-- The target of the record's event references (event, record and type; no instant).
ALTER TABLE people.attendance_event
    ADD CONSTRAINT attendance_event_record_key UNIQUE (tenant_id, id, record_id, event_type);

-- A constant default fills existing rows without an update (no row trigger fires).
ALTER TABLE people.attendance_record
    ADD COLUMN correction_version integer NOT NULL DEFAULT 0,
    ADD COLUMN corrected_at timestamptz;
ALTER TABLE people.attendance_record ALTER COLUMN check_in_event_id DROP NOT NULL;
ALTER TABLE people.attendance_record DROP CONSTRAINT attendance_record_check_in;
ALTER TABLE people.attendance_record DROP CONSTRAINT attendance_record_check_out;
ALTER TABLE people.attendance_record DROP CONSTRAINT attendance_record_check_out_shape;
ALTER TABLE people.attendance_record DROP CONSTRAINT attendance_record_one_open;
ALTER TABLE people.attendance_record
    ALTER COLUMN status SET EXPRESSION AS
        (CASE WHEN checked_out_at IS NULL THEN 'OPEN' ELSE 'COMPLETED' END);
ALTER TABLE people.attendance_record
    ADD CONSTRAINT attendance_record_check_in
        FOREIGN KEY (tenant_id, check_in_event_id, id, check_in_type)
            REFERENCES people.attendance_event (tenant_id, id, record_id, event_type),
    ADD CONSTRAINT attendance_record_check_out
        FOREIGN KEY (tenant_id, check_out_event_id, id, check_out_type)
            REFERENCES people.attendance_event (tenant_id, id, record_id, event_type),
    ADD CONSTRAINT attendance_record_correction_version CHECK (correction_version >= 0),
    ADD CONSTRAINT attendance_record_correction_shape
        CHECK ((correction_version = 0) = (corrected_at IS NULL)),
    ADD CONSTRAINT attendance_record_origin
        CHECK (check_in_event_id IS NOT NULL OR correction_version >= 1),
    ADD CONSTRAINT attendance_record_check_out_shape
        CHECK (correction_version > 0 OR ((check_out_event_id IS NULL) = (checked_out_at IS NULL))),
    ADD CONSTRAINT attendance_record_one_open
        EXCLUDE USING gist (tenant_id WITH =, employee_id WITH =,
            tstzrange(checked_in_at, check_out_until, '[]') WITH &&)
        WHERE (checked_out_at IS NULL);

COMMENT ON TABLE people.attendance_record IS
    'MVP-060A attendance on a scheduled shift (Restricted HR/Operations). The effective projection of people.attendance_event and people.attendance_correction (MVP-062): inserted open or by a correction, completed once, corrected only by a matching correction, never deleted.';

-- ---------------------------------------------------------------------------------------------
-- Correction evidence (append-only)
-- ---------------------------------------------------------------------------------------------

-- The explanation grammar (mirrored by the application, which also refuses Unicode format
-- characters): trimmed, 10 to 500 characters, no control character, markup or link.
CREATE FUNCTION people.attendance_correction_explanation_valid(p_text text) RETURNS boolean
    LANGUAGE sql IMMUTABLE
AS $fn$
SELECT p_text = btrim(p_text)
    AND char_length(p_text) BETWEEN 10 AND 500
    AND p_text !~ '[[:cntrl:]]'
    AND p_text !~ '[<>]'
    AND p_text !~* '(https?://|ftp://|www\.|mailto:)'
$fn$;

CREATE TABLE people.attendance_correction (
    tenant_id             uuid        NOT NULL,
    id                    uuid        NOT NULL,
    record_id             uuid        NOT NULL,
    version               integer     NOT NULL,
    operation             text        NOT NULL,
    schedule_id           uuid        NOT NULL,
    slot_id               uuid        NOT NULL,
    employee_id           uuid        NOT NULL,
    employment_id         uuid        NOT NULL,
    slot_start_at         timestamptz NOT NULL,
    slot_end_at           timestamptz NOT NULL,
    before_checked_in_at  timestamptz,
    before_checked_out_at timestamptz,
    after_checked_in_at   timestamptz NOT NULL,
    after_checked_out_at  timestamptz,
    reason_code           text        NOT NULL,
    explanation           text        NOT NULL,
    corrected_by          text        NOT NULL,
    preview_digest        text        NOT NULL,
    idempotency_digest    text        NOT NULL,
    corrected_at          timestamptz NOT NULL,
    CONSTRAINT attendance_correction_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT attendance_correction_version_unique UNIQUE (tenant_id, record_id, version),
    CONSTRAINT attendance_correction_record
        FOREIGN KEY (tenant_id, record_id, slot_id, schedule_id, employee_id, employment_id)
            REFERENCES people.attendance_record
                (tenant_id, id, slot_id, schedule_id, employee_id, employment_id)
            DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT attendance_correction_slot
        FOREIGN KEY (tenant_id, slot_id, schedule_id, slot_start_at, slot_end_at)
            REFERENCES people.work_schedule_slot (tenant_id, id, schedule_id, start_at, end_at),
    CONSTRAINT attendance_correction_employment
        FOREIGN KEY (tenant_id, employment_id, employee_id)
            REFERENCES people.employment (tenant_id, id, employee_id),
    CONSTRAINT attendance_correction_operation
        CHECK (operation IN ('CREATE_MISSING_RECORD', 'REPLACE_TIMES')),
    CONSTRAINT attendance_correction_version_positive CHECK (version >= 1),
    CONSTRAINT attendance_correction_shape
        CHECK (CASE WHEN operation = 'CREATE_MISSING_RECORD'
            THEN version = 1 AND before_checked_in_at IS NULL AND before_checked_out_at IS NULL
            ELSE before_checked_in_at IS NOT NULL END),
    CONSTRAINT attendance_correction_changes
        CHECK (operation = 'CREATE_MISSING_RECORD'
            OR after_checked_in_at IS DISTINCT FROM before_checked_in_at
            OR after_checked_out_at IS DISTINCT FROM before_checked_out_at),
    CONSTRAINT attendance_correction_order
        CHECK (after_checked_out_at IS NULL OR after_checked_out_at > after_checked_in_at),
    CONSTRAINT attendance_correction_check_in_window
        CHECK (after_checked_in_at >= slot_start_at - INTERVAL '2 hours'
            AND after_checked_in_at < slot_end_at),
    CONSTRAINT attendance_correction_check_out_window
        CHECK (after_checked_out_at IS NULL
            OR after_checked_out_at <= slot_end_at + INTERVAL '24 hours'),
    CONSTRAINT attendance_correction_not_future
        CHECK (after_checked_in_at <= corrected_at + INTERVAL '5 minutes'
            AND (after_checked_out_at IS NULL
                OR after_checked_out_at <= corrected_at + INTERVAL '5 minutes')),
    CONSTRAINT attendance_correction_reason
        CHECK (reason_code IN ('DEVICE_OR_CONNECTIVITY', 'SUPERVISOR_OR_EMPLOYEE_REPORT',
            'DATA_ENTRY_ERROR', 'SYSTEM_ERROR', 'OTHER')),
    CONSTRAINT attendance_correction_explanation_valid
        CHECK (people.attendance_correction_explanation_valid(explanation)),
    CONSTRAINT attendance_correction_by_length CHECK (char_length(corrected_by) BETWEEN 1 AND 255),
    CONSTRAINT attendance_correction_preview_digest CHECK (preview_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT attendance_correction_idempotency_digest
        CHECK (idempotency_digest ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE people.attendance_correction IS
    'MVP-062 attendance corrections by tenant administrators (Restricted HR/Operations; the explanation may hold personal information). Append-only; numbered per record; the before and after instants, the reason and the verified subject as committed.';

CREATE TRIGGER attendance_correction_immutable
    BEFORE UPDATE OR DELETE ON people.attendance_correction
    FOR EACH ROW EXECUTE FUNCTION people.attendance_immutable();
CREATE TRIGGER attendance_correction_no_truncate
    BEFORE TRUNCATE ON people.attendance_correction
    FOR EACH STATEMENT EXECUTE FUNCTION people.attendance_no_truncate();

-- A replacement names its record's current version and values; a creation names a slot of a
-- schedule that is not cancelled or replaced, its effective assignee with a qualifying employment,
-- and no existing record. Serializes with check-ins, check-outs and terminal actions through the
-- schedule row, then the record.
CREATE FUNCTION people.attendance_correction_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_site     uuid;
    v_first    date;
    v_last     date;
    v_assignee uuid;
    v_record   people.attendance_record%ROWTYPE;
BEGIN
    IF NEW.operation NOT IN ('CREATE_MISSING_RECORD', 'REPLACE_TIMES') OR NEW.version < 1 THEN
        -- Malformed: refused by the table's own checks (_operation, _version_positive).
        RETURN NEW;
    END IF;
    SELECT s.site_id INTO v_site FROM people.work_schedule s
        WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.schedule_id
        FOR UPDATE;
    SELECT * INTO v_record FROM people.attendance_record r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.record_id
        FOR UPDATE;
    IF NEW.operation = 'REPLACE_TIMES' THEN
        IF NOT FOUND
            OR v_record.slot_id <> NEW.slot_id OR v_record.schedule_id <> NEW.schedule_id
            OR v_record.employee_id <> NEW.employee_id
            OR v_record.employment_id <> NEW.employment_id
            OR NEW.version <> v_record.correction_version + 1
            OR NEW.before_checked_in_at <> v_record.checked_in_at
            OR NEW.before_checked_out_at IS DISTINCT FROM v_record.checked_out_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_correction_sequence',
                MESSAGE = 'a correction replaces its record''s current version and values';
        END IF;
        RETURN NEW;
    END IF;
    IF FOUND OR EXISTS (SELECT 1 FROM people.attendance_record r
            WHERE r.tenant_id = NEW.tenant_id AND r.slot_id = NEW.slot_id
                AND r.employee_id = NEW.employee_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_correction_sequence',
            MESSAGE = 'a missing record is created only while none exists';
    END IF;
    SELECT l.start_local::date, people.work_schedule_slot_last_date(l.start_local, l.end_local),
            COALESCE((SELECT d.replacement_employee_id
                FROM people.work_schedule_substitution_decision d
                WHERE d.tenant_id = l.tenant_id AND d.slot_id = l.id AND d.outcome = 'APPROVED'
                    AND d.decided_at < l.end_at), l.assigned_employee_id)
        INTO v_first, v_last, v_assignee
        FROM people.work_schedule_slot l
        WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.slot_id
            AND l.schedule_id = NEW.schedule_id;
    IF v_site IS NULL OR v_first IS NULL
        OR people.work_schedule_terminal(NEW.tenant_id, NEW.schedule_id)
        OR v_assignee IS DISTINCT FROM NEW.employee_id
        OR NOT people.attendance_employment_qualifies(NEW.tenant_id, NEW.employment_id,
            NEW.employee_id, v_site, v_first, v_last) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_correction_create',
            MESSAGE = 'a missing record is created for the effective assignee of an operational slot';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER attendance_correction_guard
    BEFORE INSERT ON people.attendance_correction
    FOR EACH ROW EXECUTE FUNCTION people.attendance_correction_guard();

-- ---------------------------------------------------------------------------------------------
-- The projection's guard, the check-out rule and the deferred agreement (replaced)
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION people.attendance_record_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_record_immutable',
            MESSAGE = 'an attendance record is never deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.correction_version = 0 THEN
            IF NEW.check_in_event_id IS NULL OR NEW.check_out_event_id IS NOT NULL
                OR NEW.checked_out_at IS NOT NULL OR NEW.corrected_at IS NOT NULL THEN
                RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_record_guard',
                    MESSAGE = 'an attendance record is inserted open';
            END IF;
            RETURN NEW;
        END IF;
        IF NEW.correction_version <> 1
            OR NEW.check_in_event_id IS NOT NULL OR NEW.check_out_event_id IS NOT NULL
            OR NOT EXISTS (SELECT 1 FROM people.attendance_correction c
                WHERE c.tenant_id = NEW.tenant_id AND c.record_id = NEW.id AND c.version = 1
                    AND c.operation = 'CREATE_MISSING_RECORD'
                    AND c.slot_id = NEW.slot_id AND c.schedule_id = NEW.schedule_id
                    AND c.employee_id = NEW.employee_id AND c.employment_id = NEW.employment_id
                    AND c.after_checked_in_at = NEW.checked_in_at
                    AND c.after_checked_out_at IS NOT DISTINCT FROM NEW.checked_out_at
                    AND c.corrected_at = NEW.corrected_at) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_record_correction',
                MESSAGE = 'a record created by a correction matches its correction';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.id IS DISTINCT FROM OLD.id
        OR NEW.schedule_id IS DISTINCT FROM OLD.schedule_id
        OR NEW.slot_id IS DISTINCT FROM OLD.slot_id
        OR NEW.employee_id IS DISTINCT FROM OLD.employee_id
        OR NEW.employment_id IS DISTINCT FROM OLD.employment_id
        OR NEW.slot_start_at IS DISTINCT FROM OLD.slot_start_at
        OR NEW.slot_end_at IS DISTINCT FROM OLD.slot_end_at
        OR NEW.check_out_until IS DISTINCT FROM OLD.check_out_until
        OR NEW.check_in_event_id IS DISTINCT FROM OLD.check_in_event_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_record_guard',
            MESSAGE = 'an attendance record keeps its shift, people and check-in event';
    END IF;
    IF NEW.correction_version = OLD.correction_version THEN
        -- The single check-out attach: a record with neither an effective nor a recorded
        -- check-out receives its check-out event and instant, and nothing else.
        IF OLD.check_out_event_id IS NOT NULL OR OLD.checked_out_at IS NOT NULL
            OR NEW.check_out_event_id IS NULL OR NEW.checked_out_at IS NULL
            OR NEW.checked_in_at IS DISTINCT FROM OLD.checked_in_at
            OR NEW.corrected_at IS DISTINCT FROM OLD.corrected_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_record_guard',
                MESSAGE = 'an open attendance record only receives its single check-out';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.correction_version <> OLD.correction_version + 1
        OR NEW.check_out_event_id IS DISTINCT FROM OLD.check_out_event_id
        OR NOT EXISTS (SELECT 1 FROM people.attendance_correction c
            WHERE c.tenant_id = NEW.tenant_id AND c.record_id = NEW.id
                AND c.version = NEW.correction_version AND c.operation = 'REPLACE_TIMES'
                AND c.before_checked_in_at = OLD.checked_in_at
                AND c.before_checked_out_at IS NOT DISTINCT FROM OLD.checked_out_at
                AND c.after_checked_in_at = NEW.checked_in_at
                AND c.after_checked_out_at IS NOT DISTINCT FROM NEW.checked_out_at
                AND c.corrected_at = NEW.corrected_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_record_correction',
            MESSAGE = 'a record is corrected only by its next matching correction';
    END IF;
    RETURN NEW;
END
$fn$;

-- V28's rule, unchanged except that a record with an effective check-out (set by a correction)
-- receives no check-out event.
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
            OR v_record.checked_out_at IS NOT NULL
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

CREATE OR REPLACE FUNCTION people.attendance_exact() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_record     people.attendance_record%ROWTYPE;
    v_record_id  uuid;
    v_in_at      timestamptz;
    v_out_at     timestamptz;
    v_correction people.attendance_correction%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'attendance_record' THEN
        v_record_id := NEW.id;
    ELSE
        v_record_id := NEW.record_id;
    END IF;
    SELECT * INTO v_record FROM people.attendance_record r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = v_record_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_exact',
            MESSAGE = 'every attendance event and correction has its record';
    END IF;
    SELECT e.recorded_at INTO v_in_at FROM people.attendance_event e
        WHERE e.tenant_id = v_record.tenant_id AND e.record_id = v_record.id
            AND e.event_type = 'CHECKED_IN' AND e.id = v_record.check_in_event_id;
    SELECT e.recorded_at INTO v_out_at FROM people.attendance_event e
        WHERE e.tenant_id = v_record.tenant_id AND e.record_id = v_record.id
            AND e.event_type = 'CHECKED_OUT' AND e.id = v_record.check_out_event_id;
    IF (SELECT count(*) FROM people.attendance_event e
            WHERE e.tenant_id = v_record.tenant_id AND e.record_id = v_record.id
                AND e.event_type = 'CHECKED_IN')
            <> (CASE WHEN v_record.check_in_event_id IS NULL THEN 0 ELSE 1 END)
        OR (v_record.check_in_event_id IS NOT NULL AND v_in_at IS NULL)
        OR (SELECT count(*) FROM people.attendance_event e
            WHERE e.tenant_id = v_record.tenant_id AND e.record_id = v_record.id
                AND e.event_type = 'CHECKED_OUT')
            <> (CASE WHEN v_record.check_out_event_id IS NULL THEN 0 ELSE 1 END)
        OR (v_record.check_out_event_id IS NOT NULL AND v_out_at IS NULL)
        OR (SELECT count(*) FROM people.attendance_correction c
            WHERE c.tenant_id = v_record.tenant_id AND c.record_id = v_record.id)
            <> v_record.correction_version
        OR COALESCE((SELECT max(c.version) FROM people.attendance_correction c
            WHERE c.tenant_id = v_record.tenant_id AND c.record_id = v_record.id), 0)
            <> v_record.correction_version THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_exact',
            MESSAGE = 'an attendance record, its events and its corrections agree';
    END IF;
    IF v_record.correction_version = 0 THEN
        IF v_record.checked_in_at IS DISTINCT FROM v_in_at
            OR v_record.checked_out_at IS DISTINCT FROM v_out_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_exact',
                MESSAGE = 'an uncorrected attendance record holds its events'' instants';
        END IF;
        RETURN NULL;
    END IF;
    SELECT * INTO v_correction FROM people.attendance_correction c
        WHERE c.tenant_id = v_record.tenant_id AND c.record_id = v_record.id
            AND c.version = v_record.correction_version;
    IF v_record.checked_in_at IS DISTINCT FROM v_correction.after_checked_in_at
        OR v_record.corrected_at IS DISTINCT FROM v_correction.corrected_at
        OR NOT (v_record.checked_out_at IS NOT DISTINCT FROM v_correction.after_checked_out_at
            OR (v_correction.after_checked_out_at IS NULL
                AND v_record.check_out_event_id IS NOT NULL
                AND v_record.checked_out_at = v_out_at)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_exact',
            MESSAGE = 'a corrected attendance record holds its latest correction''s values';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER attendance_correction_exact
    AFTER INSERT ON people.attendance_correction
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.attendance_exact();
