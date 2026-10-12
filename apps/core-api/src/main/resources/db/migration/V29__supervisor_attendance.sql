-- MVP-060B (Issue #115): an employee's effective manager records a scheduled-shift check-in or
-- check-out on a direct report's behalf, with a reason, inside the unchanged MVP-060A windows.
-- The attendance record (projection) is unchanged: it names the target employee, never the
-- manager or the reason.
--
-- Ownership: the people module; only the bounded com.divalhr.core.people.schedule package reads
-- or writes these objects (ADR 0017). The guard reads the module's own employment tables.
--
-- Classification: Restricted HR/Operations. The reason and the verified subject are evidence:
-- the reason is shown to the target employee, the subject never.
--
-- Database authority (V29):
--   * people.attendance_event gains recording_method (SELF or SUPERVISOR), immutable with the rest
--     of the event (attendance_event_immutable). Every V28 event is SELF through a migration-owned
--     default that is dropped at once, so every new event names its method explicitly;
--   * people.attendance_supervisor_action is append-only (_immutable, _no_truncate): one row per
--     SUPERVISOR event (_event_unique), bound by one composite key to that event's tenant, record,
--     schedule, slot, target employee and employment, type, instant, method, subject and
--     idempotency digest (_event); it names the manager employee and the manager's employment
--     (_manager_employment, belonging to that manager), a manager other than the target
--     (_not_self), and the reason as submitted (decision-reason grammar version 1,
--     people.leave_reason_valid; _locale, _reason_valid);
--   * authority (attendance_supervisor_action_guard): on the shift's site-local start date the
--     target's employment has an active, non-superseded MANAGER line naming the manager, and the
--     manager's employment covers that date;
--   * deferred (attendance_supervisor_exact, per event): a SUPERVISOR event has exactly one action
--     and a SELF event none.
-- No backfill beyond the method of existing events (SELF); the new table starts empty.
--
-- Rollback: db/rollback/V29__rollback.sql (manual, never run by Flyway). It restores V28 exactly
-- only while no supervisor action or SUPERVISOR event exists (existing SELF attendance survives
-- unchanged); there is no override, and it never touches flyway_schema_history.

-- ---------------------------------------------------------------------------------------------
-- The recording method of every event
-- ---------------------------------------------------------------------------------------------

-- A constant default fills existing rows without an update (no row trigger fires); dropping it
-- leaves every later insert to name its method.
ALTER TABLE people.attendance_event ADD COLUMN recording_method text NOT NULL DEFAULT 'SELF';
ALTER TABLE people.attendance_event ALTER COLUMN recording_method DROP DEFAULT;
ALTER TABLE people.attendance_event
    ADD CONSTRAINT attendance_event_recording_method
        CHECK (recording_method IN ('SELF', 'SUPERVISOR'));
-- The target of the supervisor action's single reference.
ALTER TABLE people.attendance_event
    ADD CONSTRAINT attendance_event_supervisor_key
        UNIQUE (tenant_id, id, record_id, schedule_id, slot_id, employee_id, employment_id,
            event_type, recorded_at, recording_method, recorded_by, idempotency_digest);

-- ---------------------------------------------------------------------------------------------
-- Supervisor evidence (append-only)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE people.attendance_supervisor_action (
    tenant_id             uuid        NOT NULL,
    id                    uuid        NOT NULL,
    event_id              uuid        NOT NULL,
    record_id             uuid        NOT NULL,
    schedule_id           uuid        NOT NULL,
    slot_id               uuid        NOT NULL,
    employee_id           uuid        NOT NULL,
    employment_id         uuid        NOT NULL,
    event_type            text        NOT NULL,
    recorded_at           timestamptz NOT NULL,
    recording_method      text        NOT NULL GENERATED ALWAYS AS ('SUPERVISOR') STORED,
    manager_employee_id   uuid        NOT NULL,
    manager_employment_id uuid        NOT NULL,
    reason_locale         text        NOT NULL,
    reason                text        NOT NULL,
    recorded_by           text        NOT NULL,
    idempotency_digest    text        NOT NULL,
    CONSTRAINT attendance_supervisor_action_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT attendance_supervisor_action_event_unique UNIQUE (tenant_id, event_id),
    CONSTRAINT attendance_supervisor_action_event
        FOREIGN KEY (tenant_id, event_id, record_id, schedule_id, slot_id, employee_id,
            employment_id, event_type, recorded_at, recording_method, recorded_by,
            idempotency_digest)
            REFERENCES people.attendance_event (tenant_id, id, record_id, schedule_id, slot_id,
                employee_id, employment_id, event_type, recorded_at, recording_method,
                recorded_by, idempotency_digest),
    CONSTRAINT attendance_supervisor_action_manager_employment
        FOREIGN KEY (tenant_id, manager_employment_id, manager_employee_id)
            REFERENCES people.employment (tenant_id, id, employee_id),
    CONSTRAINT attendance_supervisor_action_not_self CHECK (manager_employee_id <> employee_id),
    CONSTRAINT attendance_supervisor_action_locale CHECK (reason_locale IN ('en', 'fr')),
    CONSTRAINT attendance_supervisor_action_reason_valid CHECK (people.leave_reason_valid(reason))
);

COMMENT ON TABLE people.attendance_supervisor_action IS
    'MVP-060B supervisor-assisted attendance evidence (Restricted HR/Operations). Append-only; one row per SUPERVISOR attendance event: the manager, the manager''s employment and the reason as submitted.';

CREATE TRIGGER attendance_supervisor_action_immutable
    BEFORE UPDATE OR DELETE ON people.attendance_supervisor_action
    FOR EACH ROW EXECUTE FUNCTION people.attendance_immutable();
CREATE TRIGGER attendance_supervisor_action_no_truncate
    BEFORE TRUNCATE ON people.attendance_supervisor_action
    FOR EACH STATEMENT EXECUTE FUNCTION people.attendance_no_truncate();

-- The manager is the target employment's effective manager on the shift's site-local start date,
-- and the named manager employment is theirs on that date.
CREATE FUNCTION people.attendance_supervisor_action_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_first date;
BEGIN
    SELECT l.start_local::date INTO v_first FROM people.work_schedule_slot l
        WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.slot_id
            AND l.schedule_id = NEW.schedule_id;
    IF NOT FOUND
        OR NOT EXISTS (SELECT 1 FROM people.employment_assignment a
            WHERE a.tenant_id = NEW.tenant_id AND a.employment_id = NEW.employment_id
                AND a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL
                AND a.manager_employee_id = NEW.manager_employee_id
                AND a.effective_from <= v_first
                AND (a.effective_to IS NULL OR a.effective_to >= v_first))
        OR NOT EXISTS (SELECT 1 FROM people.employment m
            WHERE m.tenant_id = NEW.tenant_id AND m.id = NEW.manager_employment_id
                AND m.employee_id = NEW.manager_employee_id
                AND m.effective_from <= v_first
                AND (m.effective_to IS NULL OR m.effective_to >= v_first)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'attendance_supervisor_action_authority',
            MESSAGE = 'supervisor attendance is recorded by the target employment''s manager';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER attendance_supervisor_action_guard
    BEFORE INSERT ON people.attendance_supervisor_action
    FOR EACH ROW EXECUTE FUNCTION people.attendance_supervisor_action_guard();

-- ---------------------------------------------------------------------------------------------
-- Method and evidence agree at commit
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.attendance_supervisor_exact() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_method text;
    v_event  uuid;
BEGIN
    IF TG_TABLE_NAME = 'attendance_event' THEN
        v_event := NEW.id;
    ELSE
        v_event := NEW.event_id;
    END IF;
    SELECT e.recording_method INTO v_method FROM people.attendance_event e
        WHERE e.tenant_id = NEW.tenant_id AND e.id = v_event;
    IF NOT FOUND
        OR (SELECT count(*) FROM people.attendance_supervisor_action s
            WHERE s.tenant_id = NEW.tenant_id AND s.event_id = v_event)
            <> (CASE WHEN v_method = 'SUPERVISOR' THEN 1 ELSE 0 END) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'attendance_supervisor_exact',
            MESSAGE = 'a SUPERVISOR event has exactly one supervisor action and a SELF event none';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER attendance_event_supervisor_exact
    AFTER INSERT ON people.attendance_event
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.attendance_supervisor_exact();
CREATE CONSTRAINT TRIGGER attendance_supervisor_action_exact
    AFTER INSERT ON people.attendance_supervisor_action
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.attendance_supervisor_exact();
