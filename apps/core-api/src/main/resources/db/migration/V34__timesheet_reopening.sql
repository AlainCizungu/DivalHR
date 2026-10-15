-- MVP-070D (Issue #132): an APPROVED timesheet whose source attendance is materially changed by a
-- committed attendance correction is REOPENED; the organization refreshes the week (a new revision,
-- DRAFT again); the employee submits the next cycle and the routed manager decides it through the
-- MVP-070B/C workflow. Additive to V33.
--
-- Ownership: the people module; only the bounded com.divalhr.core.people.schedule package reads
-- or writes these objects (ADR 0019, ADR 0020).
--
-- Classification: Restricted HR/Operations. The reopening evidence holds identifiers, the source
-- digests before and after, the verified subject and the instant; no name, label, attendance time,
-- explanation, return reason or address.
--
-- Shape (T70D-1, T70D-3, T70D-8):
--   * people.timesheet.status: DRAFT | SUBMITTED | APPROVED | REOPENED.
--     APPROVED -> REOPENED (the REOPENED action) -> DRAFT (a refresh: the next revision)
--     -> SUBMITTED (the next cycle) -> APPROVED or DRAFT (returned), as in V33.
--   * people.timesheet_action gains REOPENED. A reopening follows the timesheet's latest action,
--     which is the approval it supersedes (superseded_action_id), in the same cycle; it binds that
--     approval's exact evidence (revision, generation version, source digest, totals, employee,
--     employment: a composite key to the approval) and its manager, the source digest after the
--     correction (always different), the committed attendance correction of the same employee
--     (correction_id), the verified subject of the correcting administrator and the instant. At
--     most one reopening per superseded approval and resulting digest, and per cycle.
--   * A generation is refreshed while none of its timesheets is SUBMITTED (V33: no SUBMITTED and no
--     APPROVED timesheet); an APPROVED timesheet is never given a revision (timesheet_guard), so a
--     refresh leaves it locked and unchanged, and a REOPENED one becomes a DRAFT only with its next
--     revision.
--   * deferred (timesheet_action_current): a reopened timesheet is REOPENED at the superseded
--     approval's revision, or a DRAFT at a later revision (refreshed, not yet submitted).
-- Earlier actions, revisions, rows, generations, corrections, attendance and schedules are
-- referenced, never changed.
--
-- Indexes: timesheet_action_reopening_unique (one reopening per approval and resulting digest),
-- timesheet_action_reopened_cycle_unique (one reopening per cycle), timesheet_action_correction
-- (the reopenings of a correction); timesheet_action_decision_unique now covers APPROVED and
-- RETURNED only.
--
-- Rollback: db/rollback/V34__rollback.sql (manual, never run by Flyway) restores V33 exactly only
-- while no reopening exists, no timesheet is REOPENED and no submitted or approved timesheet
-- belongs to a generation refreshed after its action; there is no override.

-- ---------------------------------------------------------------------------------------------
-- The states and the reopening evidence
-- ---------------------------------------------------------------------------------------------

ALTER TABLE people.timesheet DROP CONSTRAINT timesheet_status;
ALTER TABLE people.timesheet
    ADD CONSTRAINT timesheet_status
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REOPENED'));

COMMENT ON TABLE people.timesheet IS
    'MVP-070A/B/C/D weekly timesheet of one employee at one site (Restricted HR/Operations): DRAFT, SUBMITTED, APPROVED or REOPENED, the current revision and exactly its totals, the latest submission cycle and the current action.';

-- A reopening names the correction of the same employee that caused it.
ALTER TABLE people.attendance_correction
    ADD CONSTRAINT attendance_correction_employee_key UNIQUE (tenant_id, id, employee_id);

ALTER TABLE people.timesheet_action
    ADD COLUMN superseded_action_id uuid,
    ADD COLUMN after_source_digest  text,
    ADD COLUMN correction_id        uuid;

ALTER TABLE people.timesheet_action DROP CONSTRAINT timesheet_action_kind;
ALTER TABLE people.timesheet_action DROP CONSTRAINT timesheet_action_shape;
ALTER TABLE people.timesheet_action
    ADD CONSTRAINT timesheet_action_kind
        CHECK (action IN ('SUBMITTED', 'RETURNED', 'APPROVED', 'REOPENED')),
    ADD CONSTRAINT timesheet_action_after_digest
        CHECK (after_source_digest IS NULL OR after_source_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT timesheet_action_shape CHECK (CASE action
        WHEN 'SUBMITTED' THEN submission_action_id IS NULL AND return_reason IS NULL
            AND superseded_action_id IS NULL AND after_source_digest IS NULL
            AND correction_id IS NULL
        WHEN 'APPROVED' THEN submission_action_id IS NOT NULL AND return_reason IS NULL
            AND superseded_action_id IS NULL AND after_source_digest IS NULL
            AND correction_id IS NULL
        WHEN 'RETURNED' THEN submission_action_id IS NOT NULL AND return_reason IS NOT NULL
            AND people.timesheet_return_reason_valid(return_reason)
            AND superseded_action_id IS NULL AND after_source_digest IS NULL
            AND correction_id IS NULL
        ELSE submission_action_id IS NULL AND return_reason IS NULL
            AND superseded_action_id IS NOT NULL AND correction_id IS NOT NULL
            AND after_source_digest IS NOT NULL AND after_source_digest <> source_digest
        END),
    -- A reopening carries exactly the evidence of the approval it supersedes.
    ADD CONSTRAINT timesheet_action_superseded
        FOREIGN KEY (tenant_id, superseded_action_id, timesheet_id, cycle, revision,
            generation_version, source_digest, row_count, ready_rows, missing_rows, open_rows,
            changed_rows, worked_minutes, employee_id, employment_id)
            REFERENCES people.timesheet_action (tenant_id, id, timesheet_id, cycle, revision,
                generation_version, source_digest, row_count, ready_rows, missing_rows,
                open_rows, changed_rows, worked_minutes, employee_id, employment_id),
    ADD CONSTRAINT timesheet_action_correction
        FOREIGN KEY (tenant_id, correction_id, employee_id)
            REFERENCES people.attendance_correction (tenant_id, id, employee_id);

COMMENT ON TABLE people.timesheet_action IS
    'MVP-070B/C/D append-only submission, decision and reopening evidence of a timesheet (Restricted HR/Operations): the cycle, the revision with its source digest and totals, the generation version, the employee and employment, the routed, deciding or approving manager, the verified subject, the return reason, a reopening''s superseded approval, resulting source digest and correction, and the instant.';

-- One terminal decision per cycle; one reopening per cycle and per approval and resulting digest.
DROP INDEX people.timesheet_action_decision_unique;
CREATE UNIQUE INDEX timesheet_action_decision_unique
    ON people.timesheet_action (tenant_id, timesheet_id, cycle)
    WHERE action IN ('APPROVED', 'RETURNED');
CREATE UNIQUE INDEX timesheet_action_reopened_cycle_unique
    ON people.timesheet_action (tenant_id, timesheet_id, cycle) WHERE action = 'REOPENED';
CREATE UNIQUE INDEX timesheet_action_reopening_unique
    ON people.timesheet_action (tenant_id, superseded_action_id, after_source_digest)
    WHERE action = 'REOPENED';
-- The reopenings of a correction (evidence reads and the rollback's check).
CREATE INDEX timesheet_action_correction_idx
    ON people.timesheet_action (tenant_id, correction_id) WHERE correction_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- Transitions
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION people.timesheet_action_transition() RETURNS trigger
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
    IF NEW.action = 'REOPENED' THEN
        -- The approval it supersedes is the latest action of an APPROVED timesheet; its evidence
        -- (with its generation version) is bound by timesheet_action_superseded.
        IF v_sheet.status <> 'APPROVED' OR v_latest.action IS DISTINCT FROM 'APPROVED'
            OR NEW.superseded_action_id IS DISTINCT FROM v_latest.id
            OR NEW.cycle <> v_sheet.submission_cycle
            OR NEW.revision <> v_sheet.current_revision
            OR NEW.manager_employee_id IS DISTINCT FROM v_latest.manager_employee_id THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_action_transition',
                MESSAGE = 'only the current approval of an approved timesheet is reopened';
        END IF;
        RETURN NEW;
    END IF;
    SELECT version INTO v_version FROM people.timesheet_generation
    WHERE tenant_id = v_sheet.tenant_id AND id = v_sheet.generation_id;
    IF NEW.revision <> v_sheet.current_revision OR NEW.generation_version <> v_version THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_action_transition',
            MESSAGE = 'a timesheet action binds the current revision and generation version';
    END IF;
    IF NEW.action = 'SUBMITTED' THEN
        IF v_sheet.status <> 'DRAFT'
            OR (v_latest.id IS NOT NULL AND v_latest.action NOT IN ('RETURNED', 'REOPENED'))
            -- After a reopening, only the refreshed (later) revision is submitted.
            OR (v_latest.action = 'REOPENED' AND NEW.revision <= v_latest.revision)
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

-- Deferred: a timesheet's current action is its latest action and its status that action's state
-- (a reopened timesheet: REOPENED at the approved revision, or a DRAFT at a later revision).
CREATE OR REPLACE FUNCTION people.timesheet_action_current() RETURNS trigger
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
        OR NOT (CASE v_latest.action
            WHEN 'SUBMITTED' THEN v_sheet.status = 'SUBMITTED'
            WHEN 'APPROVED' THEN v_sheet.status = 'APPROVED'
            WHEN 'RETURNED' THEN v_sheet.status = 'DRAFT'
            WHEN 'REOPENED' THEN
                (v_sheet.status = 'REOPENED' AND v_sheet.current_revision = v_latest.revision)
                OR (v_sheet.status = 'DRAFT' AND v_sheet.current_revision > v_latest.revision)
            ELSE false END) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_action_current',
            MESSAGE = 'a timesheet shows its latest action';
    END IF;
    RETURN NULL;
END
$fn$;

-- A timesheet is inserted as a never-submitted DRAFT at revision 1. It changes either to its next
-- revision while it is a DRAFT, or from REOPENED to a DRAFT at its next revision (a refresh), or to
-- its new latest action (submission, return, approval or reopening) with nothing else changed;
-- never deleted. An APPROVED timesheet never receives a revision.
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
        -- A refresh: the next revision of a draft, or of a reopened timesheet (a draft again).
        IF OLD.status NOT IN ('DRAFT', 'REOPENED') OR NEW.status <> 'DRAFT'
            OR NEW.submission_cycle <> OLD.submission_cycle
            OR NEW.current_revision <> OLD.current_revision + 1
            OR NEW.updated_at < OLD.updated_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_guard',
                MESSAGE = 'a timesheet changes only to its next revision while it is a draft or reopened';
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
            WHEN 'RETURNED' THEN OLD.status = 'SUBMITTED' AND NEW.status = 'DRAFT'
                AND NEW.submission_cycle = OLD.submission_cycle
            WHEN 'REOPENED' THEN OLD.status = 'APPROVED' AND NEW.status = 'REOPENED'
                AND NEW.submission_cycle = OLD.submission_cycle
            ELSE false END) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_guard',
            MESSAGE = 'a timesheet changes state only through its matching latest action';
    END IF;
    RETURN NEW;
END
$fn$;

-- A generation is inserted at version 1; its only change is a refresh (version + 1 with the
-- refresh instant and subject), refused while any of its timesheets is SUBMITTED; never deleted.
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
        WHERE t.tenant_id = NEW.tenant_id AND t.generation_id = NEW.id
          AND t.status = 'SUBMITTED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_generation_locked',
            MESSAGE = 'a generation with submitted time is never refreshed';
    END IF;
    RETURN NEW;
END
$fn$;
