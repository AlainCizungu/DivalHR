-- MVP-070B/C (Issue #130): an employee submits a clean weekly timesheet; the employee's effective
-- manager on the site-local Sunday approves it or returns it with a reason. Additive to V32.
--
-- Ownership: the people module; only the bounded com.divalhr.core.people.schedule package reads
-- or writes these objects (ADR 0019).
--
-- Classification: Restricted HR/Operations (worked time per employee, the return reason). No name,
-- label, time, subject or reason leaves this schema through audit metadata, events, logs or errors.
--
-- Shape (T70BC-1, T70BC-4, T70BC-9):
--   * people.timesheet.status: DRAFT | SUBMITTED | APPROVED (V32: DRAFT only), with the latest
--     submission cycle (0 before the first submission) and the current action (the latest one).
--   * people.timesheet_action: append-only, numbered 1, 2, ... per timesheet: SUBMITTED (cycle n,
--     the routed manager), then exactly one RETURNED (with the reason; the timesheet is a DRAFT
--     again) or APPROVED (final in this story) for that cycle. Every action binds the revision, its
--     source digest and exact totals (a composite key to the revision), the generation version,
--     the employee and the qualifying employment of the revision's rows, the manager (routed at
--     submission, deciding at a decision; never the employee), the verified subject and the
--     instant. A decision binds its cycle's submission with identical evidence (a composite key).
--   * people.timesheet changes only through a matching action (timesheet_guard): DRAFT ->
--     SUBMITTED (cycle + 1), SUBMITTED -> APPROVED, SUBMITTED -> DRAFT (returned). Its revision
--     advances only while DRAFT (a refresh); an APPROVED timesheet never changes.
--   * A generation is never refreshed while any of its timesheets is SUBMITTED or APPROVED
--     (timesheet_generation_locked); reopening approved time is MVP-070D.
--   * deferred (timesheet_action_current): at commit a timesheet's current action is its latest
--     action and its status is that action's state.
-- Rows, revisions, generations, attendance, schedules and employment history are referenced,
-- never changed.
--
-- Indexes: timesheet_submitted (the submitted timesheets of a manager's reports, driven by the
-- manager's reporting lines); timesheet_action_sequence_unique (a timesheet's history).
--
-- Rollback: db/rollback/V33__rollback.sql (manual, never run by Flyway) restores V32 exactly only
-- while no action exists and every timesheet is a DRAFT never submitted; there is no override.

-- ---------------------------------------------------------------------------------------------
-- The return reason grammar (mirrored by the application, which also refuses Unicode format
-- characters): trimmed, 10 to 500 characters, no control character, markup or link.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.timesheet_return_reason_valid(p_text text) RETURNS boolean
    LANGUAGE sql IMMUTABLE
AS $fn$
SELECT p_text = btrim(p_text)
    AND char_length(p_text) BETWEEN 10 AND 500
    AND p_text !~ '[[:cntrl:]]'
    AND p_text !~ '[<>]'
    AND p_text !~* '(https?://|ftp://|www\.|mailto:)'
$fn$;

-- ---------------------------------------------------------------------------------------------
-- The timesheet's lifecycle columns
-- ---------------------------------------------------------------------------------------------

ALTER TABLE people.timesheet DROP CONSTRAINT timesheet_status;
ALTER TABLE people.timesheet
    ADD COLUMN submission_cycle  integer NOT NULL DEFAULT 0,
    ADD COLUMN current_action_id uuid,
    ADD CONSTRAINT timesheet_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED')),
    ADD CONSTRAINT timesheet_cycle
        CHECK (submission_cycle >= 0
            AND (submission_cycle = 0) = (current_action_id IS NULL)
            AND (submission_cycle > 0 OR status = 'DRAFT'));

COMMENT ON TABLE people.timesheet IS
    'MVP-070A/B/C weekly timesheet of one employee at one site (Restricted HR/Operations): DRAFT, SUBMITTED or APPROVED, the current revision and exactly its totals, the latest submission cycle and the current action.';

-- The submitted timesheets of an employee (a manager's queue starts from the manager's reports).
CREATE INDEX timesheet_submitted
    ON people.timesheet (tenant_id, employee_id) WHERE status = 'SUBMITTED';

-- An action binds a revision with its exact source digest and totals.
ALTER TABLE people.timesheet_revision
    ADD CONSTRAINT timesheet_revision_evidence_key UNIQUE (tenant_id, timesheet_id, revision,
        source_digest, row_count, ready_rows, missing_rows, open_rows, changed_rows,
        worked_minutes);

-- ---------------------------------------------------------------------------------------------
-- Actions (append-only)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE people.timesheet_action (
    tenant_id            uuid        NOT NULL,
    id                   uuid        NOT NULL,
    timesheet_id         uuid        NOT NULL,
    sequence             integer     NOT NULL,
    action               text        NOT NULL,
    cycle                integer     NOT NULL,
    submission_action_id uuid,
    revision             integer     NOT NULL,
    generation_version   integer     NOT NULL,
    source_digest        text        NOT NULL,
    row_count            integer     NOT NULL,
    ready_rows           integer     NOT NULL,
    missing_rows         integer     NOT NULL,
    open_rows            integer     NOT NULL,
    changed_rows         integer     NOT NULL,
    worked_minutes       integer     NOT NULL,
    employee_id          uuid        NOT NULL,
    site_id              uuid        NOT NULL,
    employment_id        uuid        NOT NULL,
    manager_employee_id  uuid        NOT NULL,
    actor_subject        text        NOT NULL,
    return_reason        text,
    occurred_at          timestamptz NOT NULL,
    CONSTRAINT timesheet_action_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT timesheet_action_sequence_unique UNIQUE (tenant_id, timesheet_id, sequence),
    CONSTRAINT timesheet_action_current_key UNIQUE (tenant_id, timesheet_id, id, cycle),
    CONSTRAINT timesheet_action_evidence_key UNIQUE (tenant_id, id, timesheet_id, cycle, revision,
        generation_version, source_digest, row_count, ready_rows, missing_rows, open_rows,
        changed_rows, worked_minutes, employee_id, employment_id),
    -- The timesheet's employee and site: the action belongs to the timesheet's owner.
    CONSTRAINT timesheet_action_owner
        FOREIGN KEY (tenant_id, timesheet_id, employee_id, site_id)
            REFERENCES people.timesheet (tenant_id, id, employee_id, site_id),
    CONSTRAINT timesheet_action_revision
        FOREIGN KEY (tenant_id, timesheet_id, revision, source_digest, row_count, ready_rows,
            missing_rows, open_rows, changed_rows, worked_minutes)
            REFERENCES people.timesheet_revision (tenant_id, timesheet_id, revision,
                source_digest, row_count, ready_rows, missing_rows, open_rows, changed_rows,
                worked_minutes),
    CONSTRAINT timesheet_action_employment
        FOREIGN KEY (tenant_id, employment_id, employee_id)
            REFERENCES people.employment (tenant_id, id, employee_id),
    CONSTRAINT timesheet_action_manager
        FOREIGN KEY (tenant_id, manager_employee_id) REFERENCES people.employee (tenant_id, id),
    -- A decision carries exactly its cycle's submitted evidence.
    CONSTRAINT timesheet_action_submission
        FOREIGN KEY (tenant_id, submission_action_id, timesheet_id, cycle, revision,
            generation_version, source_digest, row_count, ready_rows, missing_rows, open_rows,
            changed_rows, worked_minutes, employee_id, employment_id)
            REFERENCES people.timesheet_action (tenant_id, id, timesheet_id, cycle, revision,
                generation_version, source_digest, row_count, ready_rows, missing_rows,
                open_rows, changed_rows, worked_minutes, employee_id, employment_id),
    CONSTRAINT timesheet_action_kind CHECK (action IN ('SUBMITTED', 'RETURNED', 'APPROVED')),
    CONSTRAINT timesheet_action_numbers
        CHECK (sequence >= 1 AND cycle >= 1 AND revision >= 1 AND generation_version >= 1),
    CONSTRAINT timesheet_action_digest CHECK (source_digest ~ '^[0-9a-f]{64}$'),
    -- Only a clean revision is ever submitted: at least one READY row and no blocking row.
    CONSTRAINT timesheet_action_clean
        CHECK (ready_rows >= 1 AND missing_rows = 0 AND open_rows = 0 AND changed_rows = 0
            AND row_count = ready_rows AND worked_minutes >= 0),
    CONSTRAINT timesheet_action_not_self CHECK (manager_employee_id <> employee_id),
    CONSTRAINT timesheet_action_subject CHECK (char_length(actor_subject) BETWEEN 1 AND 255),
    CONSTRAINT timesheet_action_shape CHECK (CASE action
        WHEN 'SUBMITTED' THEN submission_action_id IS NULL AND return_reason IS NULL
        WHEN 'APPROVED' THEN submission_action_id IS NOT NULL AND return_reason IS NULL
        ELSE submission_action_id IS NOT NULL AND return_reason IS NOT NULL
            AND people.timesheet_return_reason_valid(return_reason)
        END)
);

COMMENT ON TABLE people.timesheet_action IS
    'MVP-070B/C append-only submission and decision evidence of a timesheet (Restricted HR/Operations): the cycle, the revision with its source digest and totals, the generation version, the employee and employment, the routed or deciding manager, the verified subject, the return reason and the instant.';

-- One submission and at most one terminal decision per cycle.
CREATE UNIQUE INDEX timesheet_action_submission_unique
    ON people.timesheet_action (tenant_id, timesheet_id, cycle) WHERE action = 'SUBMITTED';
CREATE UNIQUE INDEX timesheet_action_decision_unique
    ON people.timesheet_action (tenant_id, timesheet_id, cycle) WHERE action <> 'SUBMITTED';

-- The timesheet's current action is one of its actions of its current cycle (checked at commit).
ALTER TABLE people.timesheet
    ADD CONSTRAINT timesheet_current_action
        FOREIGN KEY (tenant_id, id, current_action_id, submission_cycle)
            REFERENCES people.timesheet_action (tenant_id, timesheet_id, id, cycle)
            DEFERRABLE INITIALLY DEFERRED;

CREATE TRIGGER timesheet_action_immutable
    BEFORE UPDATE OR DELETE ON people.timesheet_action
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_immutable();
CREATE TRIGGER timesheet_action_no_truncate
    BEFORE TRUNCATE ON people.timesheet_action
    FOR EACH STATEMENT EXECUTE FUNCTION people.timesheet_no_truncate();

-- ---------------------------------------------------------------------------------------------
-- Transitions: an action follows the timesheet's latest one, from its current state
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.timesheet_action_transition() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_sheet   people.timesheet%ROWTYPE;
    v_latest  people.timesheet_action%ROWTYPE;
    v_version integer;
BEGIN
    SELECT * INTO v_sheet FROM people.timesheet
    WHERE tenant_id = NEW.tenant_id AND id = NEW.timesheet_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23503', CONSTRAINT = 'timesheet_action_owner',
            MESSAGE = 'an action needs its timesheet';
    END IF;
    SELECT * INTO v_latest FROM people.timesheet_action
    WHERE tenant_id = NEW.tenant_id AND timesheet_id = NEW.timesheet_id
    ORDER BY sequence DESC LIMIT 1;
    IF NEW.sequence IS DISTINCT FROM coalesce(v_latest.sequence, 0) + 1
        OR v_sheet.current_action_id IS DISTINCT FROM v_latest.id
        OR (v_latest.id IS NOT NULL AND NEW.occurred_at < v_latest.occurred_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_action_sequence',
            MESSAGE = 'a timesheet action follows the latest one';
    END IF;
    SELECT version INTO v_version FROM people.timesheet_generation
    WHERE tenant_id = v_sheet.tenant_id AND id = v_sheet.generation_id;
    IF NEW.revision <> v_sheet.current_revision OR NEW.generation_version <> v_version THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_action_transition',
            MESSAGE = 'a timesheet action binds the current revision and generation version';
    END IF;
    IF NEW.action = 'SUBMITTED' THEN
        IF v_sheet.status <> 'DRAFT'
            OR (v_latest.id IS NOT NULL AND v_latest.action <> 'RETURNED')
            OR NEW.cycle <> v_sheet.submission_cycle + 1
            -- The employment is the qualifying employment of every row of the revision.
            OR EXISTS (SELECT 1 FROM people.timesheet_row r
                WHERE r.tenant_id = NEW.tenant_id AND r.timesheet_id = NEW.timesheet_id
                  AND r.revision = NEW.revision AND r.employment_id <> NEW.employment_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_action_transition',
                MESSAGE = 'only a draft is submitted, as the next cycle';
        END IF;
    ELSIF v_sheet.status <> 'SUBMITTED' OR v_latest.action IS DISTINCT FROM 'SUBMITTED'
        OR NEW.submission_action_id IS DISTINCT FROM v_latest.id
        OR NEW.cycle <> v_sheet.submission_cycle THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_action_transition',
            MESSAGE = 'only the current submission is approved or returned';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER timesheet_action_transition
    BEFORE INSERT ON people.timesheet_action
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_action_transition();

-- Deferred: a timesheet's current action is its latest action and its status that action's state.
CREATE FUNCTION people.timesheet_action_current() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_sheet  people.timesheet%ROWTYPE;
    v_latest people.timesheet_action%ROWTYPE;
BEGIN
    SELECT * INTO v_sheet FROM people.timesheet
    WHERE tenant_id = NEW.tenant_id AND id = NEW.timesheet_id;
    SELECT * INTO v_latest FROM people.timesheet_action
    WHERE tenant_id = NEW.tenant_id AND timesheet_id = NEW.timesheet_id
    ORDER BY sequence DESC LIMIT 1;
    IF v_sheet.current_action_id IS DISTINCT FROM v_latest.id
        OR v_sheet.submission_cycle IS DISTINCT FROM v_latest.cycle
        OR v_sheet.status IS DISTINCT FROM (CASE v_latest.action
            WHEN 'SUBMITTED' THEN 'SUBMITTED' WHEN 'APPROVED' THEN 'APPROVED' ELSE 'DRAFT' END) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_action_current',
            MESSAGE = 'a timesheet shows its latest action';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER timesheet_action_current
    AFTER INSERT ON people.timesheet_action
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_action_current();

-- ---------------------------------------------------------------------------------------------
-- The timesheet and generation guards learn the lifecycle (V32 definitions extended)
-- ---------------------------------------------------------------------------------------------

-- A timesheet is inserted as a never-submitted DRAFT at revision 1. It changes either to its next
-- revision while it stays a DRAFT (a refresh), or to its new latest action (submission, return or
-- approval) with nothing else changed; never deleted.
CREATE OR REPLACE FUNCTION people.timesheet_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_action people.timesheet_action%ROWTYPE;
    v_latest integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_immutable',
            MESSAGE = 'a timesheet is never deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.current_revision <> 1 OR NEW.updated_at <> NEW.created_at
            OR NEW.status <> 'DRAFT' OR NEW.submission_cycle <> 0
            OR NEW.current_action_id IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_guard',
                MESSAGE = 'a timesheet starts as a draft at revision 1';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.id IS DISTINCT FROM OLD.id
        OR NEW.generation_id IS DISTINCT FROM OLD.generation_id
        OR NEW.employee_id IS DISTINCT FROM OLD.employee_id
        OR NEW.site_id IS DISTINCT FROM OLD.site_id OR NEW.week_start IS DISTINCT FROM OLD.week_start
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_guard',
            MESSAGE = 'a timesheet keeps its identity';
    END IF;
    IF NEW.current_action_id IS NOT DISTINCT FROM OLD.current_action_id THEN
        -- A refresh: the next revision of a draft.
        IF OLD.status <> 'DRAFT' OR NEW.status <> 'DRAFT'
            OR NEW.submission_cycle <> OLD.submission_cycle
            OR NEW.current_revision <> OLD.current_revision + 1
            OR NEW.updated_at < OLD.updated_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_guard',
                MESSAGE = 'a timesheet changes only to its next revision while it is a draft';
        END IF;
        RETURN NEW;
    END IF;
    -- An action: the new latest action of this timesheet, with its state; nothing else changes.
    SELECT * INTO v_action FROM people.timesheet_action
    WHERE tenant_id = NEW.tenant_id AND id = NEW.current_action_id
      AND timesheet_id = NEW.id;
    SELECT max(sequence) INTO v_latest FROM people.timesheet_action
    WHERE tenant_id = NEW.tenant_id AND timesheet_id = NEW.id;
    IF v_action.id IS NULL OR v_action.sequence IS DISTINCT FROM v_latest
        OR NEW.current_revision <> OLD.current_revision
        OR NEW.row_count <> OLD.row_count OR NEW.ready_rows <> OLD.ready_rows
        OR NEW.missing_rows <> OLD.missing_rows OR NEW.open_rows <> OLD.open_rows
        OR NEW.changed_rows <> OLD.changed_rows OR NEW.worked_minutes <> OLD.worked_minutes
        OR NEW.updated_at IS DISTINCT FROM OLD.updated_at
        OR NEW.submission_cycle <> v_action.cycle
        OR NOT (CASE v_action.action
            WHEN 'SUBMITTED' THEN OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED'
                AND NEW.submission_cycle = OLD.submission_cycle + 1
            WHEN 'APPROVED' THEN OLD.status = 'SUBMITTED' AND NEW.status = 'APPROVED'
                AND NEW.submission_cycle = OLD.submission_cycle
            ELSE OLD.status = 'SUBMITTED' AND NEW.status = 'DRAFT'
                AND NEW.submission_cycle = OLD.submission_cycle
            END) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_guard',
            MESSAGE = 'a timesheet changes state only through its matching latest action';
    END IF;
    RETURN NEW;
END
$fn$;

-- A generation is inserted at version 1; its only change is a refresh (version + 1 with the
-- refresh instant and subject), refused once any of its timesheets is SUBMITTED or APPROVED;
-- never deleted.
CREATE OR REPLACE FUNCTION people.timesheet_generation_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_generation_immutable',
            MESSAGE = 'a timesheet generation is never deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.version <> 1 OR NEW.refreshed_at IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_generation_guard',
                MESSAGE = 'a timesheet generation starts at version 1';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.id IS DISTINCT FROM OLD.id
        OR NEW.site_id IS DISTINCT FROM OLD.site_id OR NEW.week_start IS DISTINCT FROM OLD.week_start
        OR NEW.week_end IS DISTINCT FROM OLD.week_end
        OR NEW.site_timezone IS DISTINCT FROM OLD.site_timezone
        OR NEW.period_start_at IS DISTINCT FROM OLD.period_start_at
        OR NEW.period_end_at IS DISTINCT FROM OLD.period_end_at
        OR NEW.status IS DISTINCT FROM OLD.status
        OR NEW.generated_at IS DISTINCT FROM OLD.generated_at
        OR NEW.generated_by IS DISTINCT FROM OLD.generated_by
        OR NEW.version <> OLD.version + 1 OR NEW.refreshed_at IS NULL
        OR (OLD.refreshed_at IS NOT NULL AND NEW.refreshed_at < OLD.refreshed_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_generation_guard',
            MESSAGE = 'a timesheet generation changes only by a refresh';
    END IF;
    IF EXISTS (SELECT 1 FROM people.timesheet t
        WHERE t.tenant_id = NEW.tenant_id AND t.generation_id = NEW.id AND t.status <> 'DRAFT') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_generation_locked',
            MESSAGE = 'a generation with submitted or approved time is never refreshed';
    END IF;
    RETURN NEW;
END
$fn$;
